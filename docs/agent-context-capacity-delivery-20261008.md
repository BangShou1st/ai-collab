# Agent 上下文容量策略 v2 交付报告（2026-10-08）

## 0. 状态

- 分支 `codex/context-foundation`，起点 HEAD `c911a8518c68ee363855497cd51ab96e2fd9449b`。
- 依据：[容量设计](agent-context-capacity-design-20261008.md)、
  [全面审查](agent-comprehensive-review-20261008.md)、[维护入口](agent-runtime-maintenance.md) 第 14 节。
- 本文记录**实际实现**、**实际执行过的验证**与**未验证项**，不把设计意图写成实测结果。

## 1. 已实现范围

### 1.1 兼容迁移（V64）

`V64__agent_context_policy.sql`：

- `agent_run.context_policy_version`（`smallint NOT NULL DEFAULT 1`，`CHECK IN (1,2)`）。
  既有行=1（旧语义），新根运行与终态重试派生=2，子运行继承父版本。
- `max_input_tokens` / `max_output_tokens` 改为可空；**v2 写 NULL 明确表达"无累计上限"**，
  不使用 `0`、8M、`Integer.MAX_VALUE` 或任何假额度。
- 重建 `ck_agent_run_budgets`：区分旧限额行（仍 `used <= max`）与新无限额行
  （只要求非负计数）。其余步数/工具/非负用量/版本约束未删除。
- 新增 `agent_capped_add(used, cap, delta)`（`int`/`bigint` 重载）：`cap IS NULL` 时不封顶、
  只累计；`cap` 非空时保持 `LEAST(cap, used+delta)`。**这是累计计数推进的唯一算术入口**，
  因为 SQL 的 `LEAST(NULL, x)` 会返回 NULL，会把 `NOT NULL` 计数写成 NULL。
- 迁移后现有运行的价值不变：不批量 UPDATE 旧行，不重解释旧语义。

### 1.2 策略层

- `AgentResourcePolicy`（domain，纯函数）：`V1`/`V2`、`effectiveInputCap/effectiveOutputCap`
  （v2 恒 `null`）、`remaining`、`inputExhausted`/`outputExhausted`（v2 恒 false）、
  `v2Limits(depth)`、`forRun(version, depth, skill)`。唯一策略判据。
- `AgentContextBudget`：`H = W - R - S`、`T = min(256k, floor(H*0.85))`、`L = floor(T*0.50)`。
  窗口未知保留 50k 兼容回退并标记 `estimated`。已确认窗口的 v2 不再叠加应用 50k 上限。
- `AgentRuntimeLimits`：累计上限改为 `Integer`（可空）；`forRun` 按策略选择 v1 Skill 额度或
  v2 独立额度。
- `AgentRunView`：新增 `contextPolicyVersion`、`combinedBudgetSemantics`；累计上限为可空
  `Integer`；`enforcesCumulativeTokenLimits()`。`AgentRunMappers.nullableInt` 保证
  **JDBC NULL 不读成 0**。

### 1.3 累计 token 只统计

v2 下累计输入/输出退出：Worker 预算前置检查、`AgentConvergencePolicy.decide` 的收尾判定、
`needsFinalRequest`、摘要准入、兜底请求、委派拒绝与工具可见性。
到限只来自各运行自己的有限执行额度（步数/模型轮/工具次数/活跃时长/循环检测）。

### 1.4 单次请求快照与输出封顶

- 每次请求解析一次配置（`resolveRequest`），窗口计算、输出预留、出站封顶共用同一份。
- `effectiveRequestMaxOutput` 取"模型配置单次最大输出"与（v1）"本次剩余运行输出额度"的较小值。
- `AiRequestOutputCap` 在**本次调用范围内**下发该值；OpenAI 兼容 / Anthropic / Gemini 适配器
  读 `effective(config)`。**不改用户持久模型配置**，不跨请求泄漏。

### 1.5 父子独立执行额度

- `AgentDelegationAdmission`：v2 `split` 返回子自身独立额度（步 16/工具 24，token 为 `null`）；
  `reject` 只检查"父能否发起委派（1 次工具）+ 完成综合收尾（2 步）"与委派次数。
- `resumeParent` 的 v2 分支：子消耗只累计 `*_used`/`*_actual`，**不扣减父 `steps_used`/
  `tool_calls_used`**。
- **F6**：`admitsDelegationForUpcomingTurn(facts)` 预先计入本轮模型轮的推进成本；
  `combined` 取真实持久化语义（`combinedBudgetSemantics()`），不再固定 `false`。

### 1.6 时长、超时与租约

- v2：根活跃 45 分钟、子 30 分钟；子不继承父剩余 deadline。
- 模型单次请求 10 分钟、连接 15 秒、内置工具 30 秒、MCP 120 秒；工具结果字节保护 128kB 级。
- 清除 `AgentRunEventRecorder.recordFailure` 里固定的 `active_elapsed_ms>=300000` 隐藏截停，
  改读 `AgentRuntimeLimits.forRun(...).maxRunDuration()`。
- `AgentLeaseRenewer`：长请求期间有界续租，claim epoch fencing，失去租约/取消/退出即停止；
  独立短事务，不跨 HTTP 持锁。

### 1.7 RUN_CONTEXT 多周期窗口压缩

- `AgentContextSummarizer.maybeSummarize(..., Budget, timeRemaining)` 在 `C >= T` 时执行
  RUN_CONTEXT 压缩：选择最旧、已闭合、未被覆盖的研究轨迹前缀（上限 60 条），
  无工具摘要请求 → 质量校验 → **成功提交才推进覆盖**。
- 每运行最多 4 个有效周期；每个 `scope + 源边界` 至多一个有效周期；无新增来源不重复压缩。
- 摘要落在本运行 `agent_step`（`reason='RUN_CONTEXT_SUMMARY'`），**不触碰主会话 summary**；
  子运行只生成自己的 RUN_CONTEXT。
- 提交后协调器**重新组装**主请求。

### 1.8 原文保留与正文页

- `NEWEST_TOOL_OUTPUT_CAP` 6000→48000、`OLDER_TOOL_OUTPUT_CAP` 1500→12000、
  工具观察层 3000–24000→6000–120000 字符。
- 子研究证据注入上限 6000→24000 字符。
- 正文页默认 12000 / 最多 24000 字符，常量同源；`AgentToolResultSanitizer` 32kB→128kB。

### 1.9 研究链正确性修复

| 项 | 实现 |
| --- | --- |
| F1 | 子运行 `[QUESTIONS]` 降级为"研究缺口"并正常收口，不进入用户等待态 |
| F2 | 统一 `assembleRequestMessages` 入口；子证据参与正常/降级/收尾每一次组装 |
| F3 | 章节按去重标题计（`count`）+ 片段数另记（`fragmentCount`）；续读闭合后不再报未读完；只读后缀不算已读并报前缀缺口；重复提纲不翻倍 |
| F4 | 覆盖状态按 `(documentId, snapshotId)` 建键；不同快照各自成条并把版本冲突写入 `gaps`；未知快照不合并 |
| F5 | 子运行不加载父提案（Assembler）；`composer-v2=false` 路径补齐工作状态/摘要/历史/记忆隔离 |
| F6 | 见 1.5 |
| F7 | 前端只认独占一行的控制标记；代码围栏/行内代码/引述不误判，不全局反转义 |
| F8 | 输出超额保存已返回正文为部分产出，`resumeParent` 移交正文而非只给错误码 |

## 2. 实际执行的验证

### 2.1 单元与纯函数回归（后端）

| 测试 | 项数 | 结果 |
| --- | --- | --- |
| `AgentResourcePolicyTest` | 10 | 通过 |
| `AgentContextBudgetTest` | 14 | 通过 |
| `AgentDelegationAdmissionTest` | 12 | 通过 |
| `DelegatedResearchCoverageTest` | 16 | 通过 |
| `AgentConvergencePolicyTest` | 8 | 通过 |
| `AgentRuntimeLimitsTest` | 13 | 通过 |
| `AgentContextSummarizerTest` | 25 | 通过 |

### 2.2 旧基线红绿复现（本轮真实执行）

F3/F4：把 `DelegatedResearchCoverage.java` 还原为 `HEAD` 版本后重跑同一测试类，
**16 项中 6 项失败**，症状与审查反例一致：

```
f4DifferentSnapshotsAreNotMergedIntoVerifiedCoverage    Expected size: 2 but was: 1
f4UnknownSnapshotIsNotGuessedToBeTheSameVersion         Expected size: 2 but was: 1
f3MultipleChunksUnderOneHeadingCountAsOneSection        expected: 1 but was: 2
f3RepeatedOutlineReadDoesNotDoubleTheListedSections      expected: 2 but was: 4
f3ClosedContinuationChainStopsReportingUnreadRemainder   Expecting value to be false but was true
f3SuffixOnlyReadDoesNotClaimTheHeadingWasRead            expected: 0 but was: 1
```

恢复修复后 16/16 通过。

F7：把 `agent-prose.ts` 还原为 `HEAD` 版本后重跑同一测试文件，
**18 项中 5 项失败**（代码围栏、行内代码、引述、标记后 Windows 路径、历史中间标记样本）；
恢复修复后 18/18 通过。

F6：修复前口径由 `AgentDelegationAdmissionTest.f6VisibilityReservesTheUpcomingModelStep`
显式断言（旧口径 `admitsDelegation` 为 true、修复口径 `admitsDelegationForUpcomingTurn`
为 false），因此该用例在旧基线上的失败是确定性的。

### 2.3 后端全量与前端

见第 4 节"实测清单"（本节在收尾时按真实日志补齐）。

### 2.4 隔离 PostgreSQL

所有 PostgreSQL 回归使用 Testcontainers `pgvector/pgvector:pg17` 独立容器，
**不读写生产库**（`ai-collab-postgres`）。V64 迁移在全新空库上成功应用到 v64。

## 3. 未验证项与剩余限制

- **真实模型质量验收**：本轮容量边界用纯函数与受控 fixture 验证，未真实消耗 8M token，
  也未等待 45 分钟。真实模型只验证输出质量（见实测清单），不冒充容量实测。
- **单次请求真的发出 256k 输入**：未做。验证的是触发线计算、H 边界与"超过 T 不拒绝"的判定。
- **多周期压缩在真实长研究中的效果**：自动化覆盖了周期上限、提交才推进、失败保留旧摘要；
  真实 4 周期连续压缩的质量（信息是否丢失）仍需真实资料任务观察。
- **embedding 环境**：本地 `EMBEDDING_ENABLED=false`，语义检索不可用；正文研究不受影响，
  相关场景按"检索不可用"如实说明，不当作模型/预算缺陷。
- **租约续期的真实 10 分钟请求**：以受控时钟/短周期参数验证机制，未真实等待 10 分钟。
- `L`（压缩后软目标）与 85%/50% 比例是**第一版候选策略**，不是提供商协议，也未声称是
  真实任务校准后的最优值。
- F7 只做展示层识别修复；**未**做浏览器视觉验收以外的对话工作区改动，**未**重开 SSE。

## 4. 实测清单

### 4.1 后端全量（本轮真实执行）

```
.\mvnw.cmd -o clean test
→ Tests run: 1286, Failures: 0, Errors: 0, Skipped: 11
→ BUILD SUCCESS
```

- 11 个跳过是**既有 opt-in 门控**（`BrowserAcceptanceHostTest`、`ExistingDataUpgradeRehearsalTest`、
  `RealAcceptanceHostTest`、`RealRetrievalBaselineTest`、`ReliabilityAcceptanceHostTest`、
  `ReliabilityOperationDatabaseTest`、`OllamaEmbeddingSmokeTest`、`OpenCodeZenSmokeTest` 等），
  需显式环境开关才运行；不是本轮新增跳过，也不是环境抖动被记成跳过。
- PostgreSQL 回归全部使用 Testcontainers `pgvector/pgvector:pg17` 独立容器与真实 Flyway 迁移（v1→v64），
  未读写生产库。V64 在空库与"从 V45/V53 升级"两条路径上都验证过。
- **诚实说明过程**：中途一轮全量曾出现 124 个 `NoClassDefFoundError`——那是同一 `target/` 目录
  被并发 Maven 进程（测试 + spring-boot:run）互相覆盖造成的**自我干扰**，不是代码缺陷；
  清理后单进程重跑得到上述干净结果。以下逐项由真实日志确认。

### 4.2 前端

- `vitest run src/modules/agent` → 18 文件 / **145 项通过**。
- `vue-tsc -b` → 通过（无错误输出）。

### 4.3 旧基线红绿复现（本轮真实执行，非历史报告）

- **F3/F4**：把 `DelegatedResearchCoverage.java` 还原为 `HEAD` 版本重跑同一测试类，
  **16 项中 6 项失败**，症状与审查反例逐条对应：

  | 用例 | 旧基线实际 | 修复后 |
  | --- | --- | --- |
  | f4DifferentSnapshotsAreNotMergedIntoVerifiedCoverage | Expected size: 2 but was: 1 | 通过 |
  | f4UnknownSnapshotIsNotGuessedToBeTheSameVersion | Expected size: 2 but was: 1 | 通过 |
  | f3MultipleChunksUnderOneHeadingCountAsOneSection | expected: 1 but was: 2 | 通过 |
  | f3RepeatedOutlineReadDoesNotDoubleTheListedSections | expected: 2 but was: 4 | 通过 |
  | f3ClosedContinuationChainStopsReportingUnreadRemainder | Expecting value to be false but was true | 通过 |
  | f3SuffixOnlyReadDoesNotClaimTheHeadingWasRead | expected: 0 but was: 1 | 通过 |

- **F7**：把 `agent-prose.ts` 还原为 `HEAD` 版本重跑同一测试文件，
  **18 项中 5 项失败**（代码围栏、行内代码、引述、标记后 Windows 路径、历史中间标记样本）；
  恢复修复后 18/18 通过。
- **F6**：`AgentDelegationAdmissionTest.f6VisibilityReservesTheUpcomingModelStep`
  显式断言两种口径的差别（旧口径 `admitsDelegation` 为 true、修复口径
  `admitsDelegationForUpcomingTurn` 为 false），旧基线上的失败是确定性的。
- 其余 F1/F2/F5/F8：对应行为已并入主链，并以既有 PostgreSQL 回归 + 新增断言覆盖；
  未再单独构造旧基线反例（不把"没有重跑反例"表述成"已复现红绿"）。

### 4.4 浏览器验收（bsk，真实模型）

完整记录见
[acceptance-evidence/2026-10-08/agent-context-capacity/browser-acceptance.md](acceptance-evidence/2026-10-08/agent-context-capacity/browser-acceptance.md)。
要点：

- **简单问答**：1 次工具调用，正确任务事实与状态汇总，不为扩容增加无关请求。
- **四文档对照（直接）**：4 步 / 5 次工具调用；正确识别 4 份文档并给出关键事实
  （500 人上限、验收下限 200 人/<2%、0.8% 为开发环境实测且 500 人并发未验证、
  去高低取平均、脱敏与 AES-256/备份 ≤90 天/日志 1 年）；`来源（15）`；
  明确写"四份文档均未通读全文（RELEVANT_EXCERPTS_ONLY）……不能声称全文没有某个内容"。
- **四文档逐份委派**：3 个子运行全部 `SUCCEEDED`（16/24 独立额度），父运行回收 citations
  3/4/4 条且 `coverageKnown=true`；第 4 次委派因 `maxChildren=3` 已满而**不再暴露**
  （F6），模型收到 `TOOL_NOT_ALLOWED` 后**改用本层工具直接读取第四份正文全文**并完成目标；
  父最终回答明确区分"委派成功 3 份 + 第 4 份被拒后直接取证"，不粉饰为全部委派成功。
- **诊断展示**：`Steps 4/64 · Tools 5/64 · 本运行自身（子运行额度独立，不从此处扣减）`、
  `Tokens 输入 38528（累计只统计） · 输出 2905（累计只统计）`——无累计上限时
  不展示"剩余额度/百分比"，父计数标明为自身，不与全树消耗混用。
- **刷新与跨页恢复**：离开会话页再返回，会话/回答/执行过程/查询事实完整恢复，无"继续"按钮。
- **无文档项目**：如实回答"当前项目中没有文档"并说明查询范围。

### 4.5 环境问题（单独说明，不算缺陷）

- **文档上传的浏览器路径被拦截**：Edge 扩展未开启"Allow access to file URLs"，
  `bsk upload` 的 `input` 与 `drop` 两种机制均报 `Not allowed`。按规则不修改浏览器设置绕过；
  4 份固定文档改经**同一生产后端 API**（携带 Origin 校验头）真实上传，解析/入库走同一条链路。
- **embedding 未启用**：本地 `EMBEDDING_ENABLED=false`。本轮四文档对照的引用来自语义检索
  （检索在该环境实际可用），但正文读取路径同样被验证（委派子运行走
  `get_document_outline` + `read_document_section` 读全文）。
- **本机 8080 端口有一个 10/7 启动的旧后端进程**（不含本轮代码）。为不干扰用户进程，
  本轮浏览器验收指向本机 :8081 的新构建后端，前端 vite 以
  `VITE_BACKEND_ORIGIN=http://127.0.0.1:8081` 代理；初次用 `127.0.0.1` 访问时登录 403，
  原因是 `AUTH_ALLOWED_ORIGINS` 默认只允许 `http://localhost:5173`（来源校验按设计工作），
  改用 `localhost` 后正常。

