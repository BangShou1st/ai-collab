# 可预期委派受理拒绝的父运行保留 + 预算兜底覆盖事实交付记录（2026-10-07）

> 基线：`codex/context-foundation` / `053bdb6`（代码基线含 `18bf2ee`）。
> 本轮只收口两件事：**A. 可预期的委派受理拒绝不再直接导致父运行零回答失败**；
> **B. 预算证据兜底也携带已校验的资料覆盖事实**。
> 不扩更多子 Agent、不改预算体系、不扩其他工具覆盖语义、不重开 SSE、不重设计前端。
> 证据类型：[测试] 自动测试（真实 PostgreSQL/JUnit）；[实验] 真实模型 + 真实文档浏览器实测；[环境] 运行环境事实。

## 1. 接手基线与问题确认

实际检查：分支 `codex/context-foundation`，HEAD `053bdb6`，暂存区为空，工作区仅 `.freebuff/`、
`.dsh-acl-recovery/` 未跟踪（保留，未 reset、未 push）。

**问题 A（已确认的既有可靠性缺陷）**：`AgentToolCallExecutor.executeDelegation` 把受理拒绝
直接交给 `failWriteProposal`，后者无条件 `recordFailure` → 运行终态 FAILED。历史真实运行
`3c812574`（父）/`b3471ebb`（子）即此形态：子运行 SUCCEEDED 且带回覆盖事实，父运行在综合前
**再次**请求委派、被预算拒绝受理后整轮 FAILED，**零回答**，已有子产出未形成最终回答。
本轮修的是收尾口径，不是覆盖传递引入，也不能只归咎于模型选择。

**问题 B**：`completeFromEvidence` 的预算兜底只使用子运行文字产出（`DELEGATION_COMPLETED.content`），
不并入既有持久 `coverage`，因此兜底路径可能把"已取得提纲"表述成"未取得提纲"。

## 2. 交付 A：可预期受理拒绝的类型化区分与有边界保留

### 2.1 类型化区分（不按消息文本判断）

新增 `common/exception/AgentDelegationNotAdmittedException`，只表示"这次委派在当前运行预算下
**不可能被受理**"。原因以**稳定错误码**表达（枚举常量，非中文消息匹配）：

| 原因码 | 含义 |
| --- | --- |
| `AGENT_DELEGATION_CHILDREN_EXHAUSTED` | 本次运行的委派次数已用完 |
| `AGENT_DELEGATION_BUDGET_INSUFFICIENT` | 父运行剩余预算无法容纳子运行最小研究与收尾 |

新增纯函数 `agent/domain/model/AgentDelegationAdmission`（无数据库、无配置）：
受理判定 `reject(Facts)` 与子预算切分 `split(Facts)`。判定只读**单调变化**的运行事实
（步数/工具/输入/输出只增不减、`children_used` 只增不减），同一事实重复判定结论一致
（恢复重放不产生新的拒绝语义）。`AgentRunEventRecorder.documentResearchDelegationResult`
改用该判定，切分公式与阈值完全保持上一轮语义（子步数 `min(8, 父剩余−委派步成本−2)`、
子工具 `min(8, 父剩余−1)`、输入/输出父剩余对半封顶、最小步数 3 等）。

**没有**把 `AGENT_TOOL_NOT_ALLOWED` 一律软化：depth 边界（"子运行不能再委派"）、
定时运行策略拒绝等仍是普通 `BusinessException`，走原失败边界（有专门用例锁定）。

### 2.2 拒绝的行为（`AgentToolCallExecutor.rejectDelegation`）

- **不创建子运行、不写成功 DELEGATED 回执、不宣称已受理**：结果 `status=REJECTED`、
  `delegationAdmitted=false`、`error=<稳定原因码>`，落在委派工具**自己的 invocation 身份**上
  （`toolCallId` + `arguments` + 当前模型轮次）。
- **模型轮次正确消费**：`recordToolResult` → `markBatchHandled` → `requeueRun`，运行重新排队。
  崩溃/接管恢复时 `knownInvocationResult` 直接复用该结果——**不重复执行、不重复计数、不创建第二个子运行**。
- **已有可信产出且总结可负担时优先无工具综合**：复用既有 `AgentConvergencePolicy.decide(...,
  summarizableChildEvidence)` 与 `completeFromEvidence`，未新增第二套循环。
- **无产出但仍能执行时按既有权限与预算继续**：`EXHAUSTED` 判定不成立就照常发下一次模型请求；
  **用户明确要求委派时不得偷偷改为直接研究**（拒绝如实标注，模型自行改用其它工具）。
- **总结也无法负担时复用证据兜底**：交付已有产出并如实说明未完成范围（见 2.4）。
- **无证据时明确说明限制，不伪造答案，也不无限重排队**：走既有 `EXHAUSTED` 语义。
- **不反复请求不可能受理的委派**：`AgentRuntimeCoordinator.withoutUnadminttableDelegation`
  在受理判定为拒绝时把委派工具从**本轮暴露列表**移除。依据运行事实而非异常消息；
  只影响新请求的工具暴露，恢复已持久化轮次仍按其原文校验。
- **同 invocation 的已受理动作仍按幂等规则恢复**：`knownInvocationResult` 先于受理判定生效，
  不因后来预算变化变成新的拒绝。
- **权限、安全、暂停、取消、租约异常保持原边界**：只有本类型被软处理；`AGENT_RUN_PAUSED`
  仍走 `pauseIfRequested` 收口为 PAUSED，**暂停不被拒绝处理自动解除**。

### 2.3 未新增项

无新表、无迁移、无新事件类型（`agent_tool_invocation.status` 的 `REJECTED` 是 V47 既有取值）、
无新平台、无新错误码枚举、无第二套兜底。

## 3. 交付 B：预算兜底携带已校验覆盖事实

`completeFromEvidence` 新增 `collectedChildCoverage(steps)`，从**既有持久**
`DELEGATION_COMPLETED.coverage` 复用既有渲染能力 `DelegatedResearchCoverage.renderForParentPrompt`
（同一提取器、同一文本），与 UNTRUSTED 子文字产出**分开**附在兜底交付文本中：

- 两条兜底分支（无自有证据的子产出分支、有自有证据分支）都并入覆盖事实；
- 已取得提纲不再被误称未取得；`HEURISTIC_HEADINGS` 保留"启发式标题识别，不代表完整目录"；
- 检索命中不等于正文读完；分页/截断与未知范围如实保留；
- 旧记录缺 `coverage` 时渲染 `UNKNOWN_FACTS`（"覆盖事实：未知"），**不**解释成"未取得提纲"；
- 显式声明覆盖事实与子运行文字不一致时**以覆盖事实为准**，不让子文字覆盖已校验事实；
- 保留既有来源身份校验（citations），文档标题等内容仍是数据、不升级为指令；
- **不追加统计工具调用、不回传全部工具原文、不新增表/平台/第二套兜底**。

兜底文本是**面向用户的交付文本**（depth=0 落为会话回答；depth=1 随回收回到父运行），
因此只陈述事实与优先级，不写提示词指令。

## 4. 测试（[测试]）

### 4.1 红-绿流程（先复现再修复）

在**未修复的基线代码**上先跑新增用例（临时 stash 三份生产改动文件后运行），实际红灯：

```
expected: QUEUED but was: FAILED        （expectedDelegationRejectionMustNotFailParentRunWithZeroAnswer）
expected: "AGENT_DELEGATION_BUDGET_INSUFFICIENT" but was: null
预算兜底必须携带已校验覆盖事实块 —— budgetFallbackMustCarryVerifiedCoverageFacts 断言失败
Tests run: 3, Failures: 3, Errors: 0
```

红灯形态与真实运行 `3c812574` 一致（受理拒绝 → 父运行 FAILED、零回答）。修复后同批用例转绿。

### 4.2 本轮新增用例（按本轮实际结果单独统计）

| 用例类 | 新增 | 覆盖点 | 结果 |
| --- | --- | --- | --- |
| `AgentDelegationAdmissionTest`（新增，纯函数） | 6 | 满预算受理与切分、次数耗尽原因、推进预算不足原因、任一维度不足、COMBINED 步成本、判定稳定性与原因码互异 | 6 通过 |
| `AgentDelegationPostgresTest` 新增用例 | 12 | 见下 | 12 通过（该类 28→40） |

新增集成用例（真实 PostgreSQL + 生产协调器/执行器）：

- `expectedDelegationRejectionMustNotFailParentRunWithZeroAnswer`（A 的核心红绿用例：拒绝后非 FAILED、
  `REJECTED` 回执、无子运行、下一次准入无工具综合 SUCCEEDED 有回答）
- `exhaustedDelegationCountIsDistinctExpectedRejection`（次数耗尽为独立原因码 + 可见性收窄）
- `nonExpectedToolNotAllowedIsNotTypedAsAdmissionRejection`（同错误码但非受理拒绝类型不被软化）
- `rejectedDelegationIsConsumedOnceAndNotReplayedOnRecovery`（恢复不重复执行/计数/建第二个子运行）
- `rejectionWithoutEvidenceKeepsRunExecutableWithinExistingBudget`（无证据时继续执行，非零回答失败）
- `rejectedDelegationToolIsNoLongerExposed`（可见性收窄）
- `permissionFailureStillKeepsOriginalFailureBoundary`（权限语义保持，无受理拒绝标记）
- `pauseStillWinsOverDelegationRejectionHandling`（暂停不被自动解除）
- `budgetFallbackMustCarryVerifiedCoverageFacts`（兜底携带覆盖事实，提纲已取得不被误称未取得）
- `budgetFallbackTreatsLegacyRecordWithoutCoverageAsUnknown`（旧记录按未知兼容）
- `budgetFallbackReportsOutlineOnlyCoverageHonestly`（提纲级/局部覆盖如实）
- `verifiedCoverageFactsWinOverChildTextClaims`（子文字与覆盖事实冲突时以覆盖事实为准）

### 4.3 回归

- 委派类：`AgentDelegationPostgresTest` 40/40（28 项基线用例全部保留，无删除、无改写语义）。
- 定向批次（收口改动后的最终代码）：**253 项，0 失败，0 错误，BUILD SUCCESS**
  （暂停续跑 19 + 持久恢复 23 + 崩溃接管 5 + 仓库 40 + 协调器 32 + 行为 25 + 模型配置切换 9 +
  写提案装配 2 + 消息组装 16 + 收敛策略 8 + 用量 19 + 委派 40 + 受理判定 6 + 覆盖纯函数 9；
  日志 `ai-collab-backend/target/regression-rejection-admission.log`）。
- 全量 `mvnw test` **两次运行，均未出现一次全量零失败**，如实记录：

  | 运行 | 结果 | 说明 |
  | --- | --- | --- |
  | 第一次（`full-test-rejection-admission.log`） | 1239 项，0 失败，0 错误，11 跳过，BUILD SUCCESS | 修复期间、最终收口改动前 |
  | 第二次（`full-test-final-committed.log`，提交后同代码） | 1239 项，0 失败，**1 错误**，11 跳过 | 唯一错误 `expectedDelegationRejectionMustNotFailParentRunWithZeroAnswer` 的 `CannotGetJdbcConnectionException: Failed to obtain JDBC Connection`（Testcontainers 连接抖动），非断言失败 |

  该错误单独重跑该类 **40/40 通过、BUILD SUCCESS**（`rerun-AgentDelegationPostgresTest.log`）。
  这类 JDBC 连接抖动与上一轮全量中出现的同类错误性质一致（上一轮为
  `PersistedModelTurnRecoveryPostgresTest`），本轮不写成"一次全量零失败"。
- 新增总量核对：全量 1239 = 上一轮同口径 1221 + 本轮新增 18（委派集成 12 + 受理判定纯函数 6）；
  `AgentDelegationPostgresTest` 28 → 40。
- 保留回归：COMBINED/SEPARATED 预算语义、暂停续跑、持久恢复、崩溃接管、父子隔离、
  引用校验、父子用量结算等既有用例全部包含在上述批次与全量运行内并通过。

## 5. 真实模型验收（[实验]，browser-skill/bsk 实测 Web UI，后台、仅电脑端）

[环境] 后端本机 `mvnw spring-boot:run -Dspring-boot.run.profiles=local`（8080，devtools 于
23:08:46 重启并加载本轮代码），vite dev（5173）；项目「Zen规划冒烟」4 份文档 READY；
模型 `mimo-v2.6-flash-free`（Zen 预置，与生产一致）。浏览器经 `bsk` 后台会话
（`session start --no-focus`），验收后已 `session stop`，**未停止**原有后端与 vite。

| 实验 | 提示词形态 | 父运行 | 子运行 | 用户可见交付 |
| --- | --- | --- | --- | --- |
| 委派实测（`cd0c4a98`/`1c0d92f6`） | 显式要求委派（与前几轮固定样本同形） | **SUCCEEDED**，9/12 步、8/8 工具、1/3 子运行 | SUCCEEDED，4 步、4 工具，取得 2 份文档提纲、0 正文 | 如实交付：**"仅取得两份文档的提纲，未读取任何章节正文"**、逐份提纲清单、`HEURISTIC_HEADINGS` 边界声明 |
| 对照实测（`362e9d00`） | 普通提问（无委派字样） | **SUCCEEDED**，3/12 步、2/8 工具、**0 子运行** | 无 | 直接 `search_project_knowledge`×2 作答，并如实标注"检索片段，非全文已读" |

**关键验证点**：

1. **父运行零回答失败未复现**：显式委派提示词下父运行正常收口并交付最终回答；
   历史同形运行的 FAILED 形态（`3c812574`）不再出现。
2. **覆盖声明准确**：该次子运行只到提纲级，父回答**没有**把它说成正文读完，
   也没有反向误称"未取得提纲"，并保留启发式提纲边界提醒。
3. **委派是显式触发而非默认行为（对照实验证明）**：普通提问 `children_used=0`、
   `DELEGATION_REQUESTED` 步骤数为 0、`agent_run` 子运行数为 0，仅 2 次直接检索。
4. **现有控制行为保留**：会话历史中既有的"已完成/失败/超出预算"状态、运行详情
   （Steps/Tools、参考步骤、诊断与资源）、场景选择器与发送按钮行为均正常。
5. **前端展示正常**：委派运行在 UI 显示"Agent 已完成 / Steps 9/12 · Tools 8/8"，
   最终回答完整渲染（截图 `delegation-run-display.png`）。

**未由真实模型触发的路径（如实标注）**：本次真实重跑**一次委派即被受理成功**，
模型未再次发起委派，因此"受理拒绝后保留父运行"的运行时路径**未**由真实模型自然触发；
该路径由 4.2 的生产组件集成测试（真实 PostgreSQL + 生产协调器与执行器）证明。
按验收要求未反复碰运气消耗模型额度。

证据：`docs/acceptance-evidence/2026-10-07/rejection-admission/`。

## 6. 仍未验证 / 已知限制

- **真实模型未自然触发受理拒绝路径**（见 5）：拒绝后的无工具综合、继续执行与可见性收窄
  由集成测试证明，未取得真实模型自然触发的运行证据。
- 覆盖提取仍只识别文档研究工具（outline/read/search）；其他只读工具的覆盖语义未纳入。
- 真实 HTTP Last-Event-ID 补回、父 RUNNING 竞争窗口用量回收等保持既有待验证清单，本轮未动。
- `estimated_cost` 列与精确计费维持低优先级，未动。
- 不把 `SUCCEEDED` 等同于研究范围完整覆盖：本轮真实委派运行终态 SUCCEEDED，但覆盖只到提纲级，
  回答已如实声明。

## 7. Git 提交

1. `feat(agent)`：可预期委派受理拒绝的类型化区分与父运行有边界保留 + 预算兜底携带已校验覆盖事实
   （新增 `AgentDelegationAdmission`、`AgentDelegationNotAdmittedException`、执行器拒绝路径、
   协调器覆盖兜底与工具可见性收窄、受理判定改造、新增单测与委派集成回归）。
2. `docs(agent)`：本交付记录 + 验收证据 + 维护入口更新。
