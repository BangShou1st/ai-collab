# 上下文可靠性 D1-D8 收口交付报告（2026-10-08 第三轮）

## 0. 状态

- 分支 `codex/context-foundation`，起点 HEAD `2cf4c51`（代码/测试前置 `492fddf`、`03df846`）。
- 依据：[D1-D8 扩展复核](agent-context-capacity-expanded-review-20261008.md)、
  [D1/D2 后续复核](agent-context-capacity-c1c5-followup-review-20261008.md)、
  [衔接提示词](agent-context-capacity-d1d8-handoff-20261008.md)、维护文档第 14/15 节。
- 红绿证据：[acceptance-evidence/2026-10-08/d1d8-delivery](acceptance-evidence/2026-10-08/d1d8-delivery/README.md)。
- 本轮范围：三个开发包收口 D1-D8；不新增累计 token 限制、不迁框架、不重开 SSE、不重做 UI。
  暂停仍走明确输入续跑；已受理后台规划/摘要按既有契约完成。

## 1. 修复内容

### A. 证据压缩与消费一致性（D1/D3/D6）

- **D1**（`AgentModelMessageComposer.composeV2`）：partial 首条记录不再被按整条完全覆盖排除。
  工具观察层过滤改为"完全覆盖才退出原文视图"：`sequence <= sourceThroughSequence` 且
  **非** 上一周期 partial 记录的记录才跳过；partial 记录（`sourcePartialSequence` 匹配且
  `sourcePartialChars > 0`）整条保留在主请求中——未送入摘要的尾部重新可见，已送入前缀
  允许有界重复，不新建压缩协议、不制造孤立 ToolResult。兼容已持久化的 partial 元数据。
- **D3**（`AgentContextSummarizer`）：显式截断（`finishReason=LENGTH`）的辅助产物不能发布推进覆盖。
  - RUN_CONTEXT 压缩：`runContextQualifies(text, finishReason)` 增加截断判定，截断结果以
    `DOWNSGRADED_UNQUALIFIED` 结算实际用量，保留旧有效摘要与原始来源。
  - 会话摘要：截断草稿**不进入重压缩**（重压缩只能缩短已有草稿，无法恢复被截断丢失的尾部），
    直接以 `SUMMARY_TRUNCATED_FINISH_REASON` 降级；重压缩响应自身被截断同样降级
    （`RECOMPRESS_TRUNCATED_FINISH_REASON`）。不按句末标点猜测完整性，不给正常最终回答
    加累计输出限制，不做无界重压缩。
- **D6**（`AgentModelMessageComposer.composeV2`）：工具观察循环不再把 `toolUsed < toolShare`
  写在遍历条件内——空间耗满后停止选择，但更旧的未访问相关来源按原始体积计入
  `droppedSourceChars`（裁前估算）；明确去重的来源不计入。压缩触发不再被"先裁掉来源
  后的体积"骗过。

### B. 摘要发布并发安全（D2，`AgentRunEventRecorder.completeRunContextAttempt`）

- 检查 SELECT 对 `agent_run` 行加 `FOR UPDATE`（短事务行锁，与 requestPause/cancel/
  claim 接管的锁序一致——均为先取运行行；目标修订行其后读取），与并发控制动作串行化。
- 租约有效期参与 fencing：租约过期未换 epoch 的旧 worker 以 `RUN_CONTEXT_LEASE_EXPIRED`
  拦截（此前仅比对 epoch，无法覆盖该场景）。无 claim 上下文（HTTP 管理路径）沿用旧语义。
- 发布 UPDATE 改为**原子条件发布**：`FROM agent_run` 重新约束
  `claim_version = COALESCE(epoch, claim_version)`、`lease_expires_at > now()`、
  `cancel_requested_at IS NULL`、`status='RUNNING'`、目标修订一致（期望非 NULL 时）、
  step 仍为 `ATTEMPTED`。条件不满足转换零行 → 调用方得到 FENCED 结算（用量幂等入账），
  不把过期返回发布成有效摘要。
- 已保留：同 attempt 发布/结算只一次；fenced 返回不推进有效覆盖但用量如实结算；
  已受理暂停允许在途摘要完成、不自动解除；不跨模型 HTTP 持锁；调用方/日志可区分
  COMMITTED 与 FENCED/CAS_CONFLICT。

### C. 每次请求准备与协议转换一致性（D4/D5/D7/D8）

- **D4**（Composer 两条路径）：`composeV2` 项目记忆注入增加子运行排除
  （`memories != null && !isChildResearchRun(run, skill)`）；`buildMessageHistory`
  回退路径已有同一判定（本轮回归锁定）。主运行镜像用例验证非空真实记忆选择正常注入。
- **D5**（Summarizer + Coordinator）：单次窗口不再跨请求共享。
  - 会话摘要/重压缩/RUN_CONTEXT 压缩按**自己的配置快照 H** 核对准入（C5 已引入）；
    v2（无累计上限）不再用"主请求预算扣除后的余额"（可能为负）否决可独立发送的摘要。
  - v1 保留真实运行累计余量扣减与输出预留复查（`enforcesCumulativeTokenLimits()` 分支）。
  - 时间/时长保护不变；不伪造累计额度。
- **D7**（`LegacyReadOnlyAgentExecutor`）：实际出站转换保留 Composer/统一组装入口的
  **全部** System 层（核心规则 + 可信提案 + 子证据 + 本轮/收尾指令按序拼接）与
  **全部** User 层（工作状态、会话/RUN_CONTEXT 摘要、页面上下文、当前请求等按序附加）。
  断言落在实际 `ChatCompletionCommand`（`systemPrompt`/`userPrompt`），不只看中间 messages。
  只读、审批、来源模式与权限边界不变；不给 Legacy 新增业务写或摘要生成能力。
- **D8**（Coordinator + Summarizer）：`maybeSummarize` 新增详细结果
  `SummaryOutcome(committed, auxiliaryAttempted)`——区分"提交了摘要"与"发过辅助出站
  （含失败/不合格/fenced/暂停/取消）"。协调器在**辅助请求实际发起过后**刷新主请求快照
  （steps、模型配置、单次请求预算、子证据），不以摘要成功为前提；未发起辅助请求时保持
  "每请求一次解析"语义（`AgentRuntimeRequestSnapshotTest` 既有契约不回退）。在途请求
  仍用各自快照完成，禁止 HTTP 层重读配置造成请求内部漂移。

## 2. 红绿证据（未修基线 2cf4c51 实测）

### 2.1 mock 回归（`AgentContextReliabilityD1D8RegressionTest`，8 项）

未修基线（修复前提交）运行：**8/8 失败**，失败消息与历史探针一一对应：

| 用例 | 基线失败消息（节选） | 修复后 |
| --- | --- | --- |
| partialTailRemains… | tail missing after partial summary（UNSENT_TAIL_4907 缺失） | PASS |
| lengthTerminatedRunContext… | expected false but was true（LENGTH 仍 COMMITTED） | PASS |
| lengthTerminatedConversation… | completeSummaryAttempt 参数不符（LENGTH 进入提交/重压缩路径） | PASS |
| childV2DoesNotInheritProjectMemory… | depth=1 v2 request includes parent preference | PASS |
| nearWindowV2ConversationSummary… | summaryCalls=0（v2 摘要未发送） | PASS |
| fullToolLayerStillCounts… | droppedSourceChars=0 < 180011 | PASS |
| legacyOutboundKeeps… | outbound command dropped layers: summary=false | PASS |
| failedAuxiliaryStillRefreshes… | outbound order=[model-B, model-A]（旧快照） | PASS |

### 2.2 真实 PostgreSQL 并发回归（`AgentRunContextCommitPostgresTest`，6→8 项）

Testcontainers `pgvector/pgvector:pg17` + 真实 Flyway v1-v64 + 真实事务。未修基线新增 2 项红灯：

- `expiredClaimWithoutEpochChangeCannotPublishRunSummary`：租约过期未换 epoch 仍发布 COMMITTED（基线 FAIL）；
- `cancelCommittedBetweenCheckAndPublishCannotBecomeValidSummary`：检查 SELECT 返回后、
  发布 UPDATE 前，另一真实连接提交取消，发布仍 COMMITTED（基线 FAIL）。

修复后 8/8 通过（含既有幂等/取消/失去 claim/目标修订/真实消费/两周期 6 项）。
并发用例接受两种合法交错：取消先提交 → FENCED（不发布、用量结算）；发布先提交（行锁
串行化的合法顺序）→ COMMITTED，随后取消生效。门控 JDBC 只控制时间顺序，SELECT/UPDATE
与事务均真实执行。

## 3. 实测清单

### 3.1 受影响既有回归（本轮真实执行，修复后）

Compaction 4、Auxiliary 3、Legacy 8、Summarizer 25、ComposerV2 16、Coordinator 32、
RequestSnapshot 3、ComposerRequestConsistency 6、RoutingRequestConsistency 2、Budget 14、
Behavior 25——合计 **138 项，0 失败**（见 3.3 全量统计）。
`AgentRuntimeRequestSnapshotTest` 的"每请求一次解析"用例在未修基线通过、初版 D8 实现下
失败（resolves=2），按 D8 语义修正为"辅助请求发起过后才刷新"后恢复通过——该适配是
语义修正而非断言放宽。

### 3.2 后端全量

```
Tests run: 1309, Failures: 0, Errors: 0, Skipped: 11 — BUILD SUCCESS（见第 5.1 节过程记录）
```

### 3.3 前端

本轮无前端/共享 DTO 改动，未重跑前端全量（衔接文档允许）。

## 4. 边界与未验证项

- 未真实发送 256k 输入、未消耗 8M token、未等待 45/30 分钟——全部经受控窗口/受控模型/
  真实 PostgreSQL 验证。
- 多周期压缩的**真实模型摘要质量**（信息是否丢失）依赖浏览器有界验收实际触发压缩周期；
  未触发则如实记为未验证，不以未压缩 SUCCEEDED 冒充。
- D2 的并发交错证明覆盖"租约过期未换 epoch"与"检查与发布之间取消"两探针场景 +
  既有 claim 接管/目标修订场景；"接管的并发回归"沿用既有 `staleWorkerWithLostLease` 行锁路径。
- `droppedSourceChars` 仍是字符级保守估算（chars/3 同源）。
- Legacy 出站为单条 system + 单条 user 的协议形态不变（提供商协议约束）；本次修复保证
  语义层完整进入该形态，不改变协议本身。

## 5. 实测数据（全量/浏览器）

### 5.1 后端全量（本轮真实执行）

```
.\mvnw.cmd -o test
→ Tests run: 1309, Failures: 0, Errors: 0, Skipped: 11  BUILD SUCCESS
```

- 11 个跳过为既有 opt-in 门控，非本轮新增；PostgreSQL 类全部使用隔离 Testcontainers
  `pgvector/pgvector:pg17` + 真实 Flyway，未访问业务库。
- 过程记录：首次 `clean test` 因日志文件写入 target 被 clean 插件自身删除而失败
  （环境操作失误，非代码问题）；改为外部日志后全量一次通过。红灯复核通过
  `git stash` 在未修基线工作区临时还原执行（mock 8/8 失败、PostgreSQL 2/2 失败），
  复核后恢复修复并复跑绿灯，不留中间状态。

### 5.2 浏览器验收（bsk，后台 `--no-focus`，桌面正常尺寸）

- **新构建确认**：本轮后端启动于空闲端口 18080（mvn spring-boot:run，local profile），
  前端 Vite dev 15173 显式代理到 18080（`VITE_BACKEND_ORIGIN`）；
  `netstat` 核对 18080 监听 PID 为本轮 00:32 启动的 java 进程，不误用用户既有 8080
  （10/07 启动，本轮未触碰、未停止）。回答区显示本轮处理模型 `mimo-v2.6-flash-free`，
  数据库 `agent_step` 中本次 3 个 MODEL_TURN 均为该模型，事件与运行落库连续。
- **基本问答**：复用历史问题"项目里当前有几个任务"（total=0 如实回答，无扩容）。
- **四文档研究**（新提问）："请逐份深入阅读四份文档正文，对照报名容量上限、评分聚合
  规则、脱敏要求、验收标准关键阈值，要求引用来源并如实说明未读内容"——
  运行 SUCCEEDED：4 步/4 工具、累计输入 38749 / 输出 2091 tokens；回答覆盖四个主题、
  引用文档原文（如"单次导入上限 500 人""导入失败率实测 0.8%"）、标注
  `trust=SOURCE_DATA_ONLY` 边界与"文档记录时描述≠现状已验收"；**来源（15）**卡片为
  持久化来源投影。
- **刷新恢复**：重进项目与 Agent 页后，会话历史、运行状态（已完成）、回答与来源完整
  恢复；无"继续"按钮。
- **压缩触发事实**：本次运行未触发 RUN_CONTEXT 压缩周期（`agent_step` 中 30 分钟内
  `RUN_CONTEXT_SUMMARY` 记录为 0；活跃上下文约 12.9k tokens/轮，低于触发线）。
  按衔接文档口径，**多周期真实压缩质量记为未验证**——压缩闭环的正确性由
  真实 PostgreSQL 回归（两周期推进、partial 续读、fencing）覆盖，不以未压缩的
  SUCCEEDED 冒充质量通过。触发性修改用户 AI 配置未做（不改生产配置）。
- 验收后已停止本轮启动的 18080 后端与 15173 前端（job kill + 端口核对归零），
  浏览器会话 `session stop`，不影响用户既有服务与页面。
