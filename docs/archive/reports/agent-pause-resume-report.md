# 主动暂停与输入续跑交付报告

日期：2026-10-06。分支 `codex/context-foundation`，基线 HEAD `d5f09ab` + 全部继承未提交改动。本轮在其上一次性交付 Agent 主动暂停与输入续跑。改动保留未提交，未推送、未合并 main、未部署，未触业务数据库。

## 1. 范围与现场

- 继承现场：M1–M4 结构调整、R1/R2 恢复可靠性补修等全部保留；R1/R2 未重复开发，既有实现与回归原样通过。
- 本轮只做：`PAUSED` 非终态 + `pause_requested_at` + pause/resume 控制用例 + 输入续跑意图分流 + 前端交互。未做子 Agent 全树控制、终态跨运行续接、强制立即中断、逐字流式、精确计费、通用 checkpoint 平台。
- 用户确定的体验：暂停按钮（非终态可暂停）＋"结束本次运行"（原取消，次要操作）；**无"继续执行"按钮**，恢复只能通过原输入框的明确续跑表达，经后端统一入口分流到 resume 用例，恢复同一个 runId，不覆盖原目标/约束/goalRevision，不重复已完成动作。

## 2. 迁移与状态语义

### 2.1 迁移（V61__agent_pause_resume.sql，不改写旧迁移）

1. `agent_run` 新增 `pause_requested_at TIMESTAMPTZ`（暂停意图落库时间；RUNNING 上的意图写入**不递增 version**，不使在途响应的落库 CAS 失配）。
2. `ck_agent_run_status` 增加 `'PAUSED'`。
3. `ck_agent_run_event_type` 增加 `RUN_PAUSE_REQUESTED` / `RUN_PAUSED` / `RUN_RESUMED`（事件与状态同事务写入，须在 CHECK 中放开）。

### 2.2 状态行为（与任务书状态表一一对应）

| 当前状态 | 暂停 | 继续 |
| --- | --- | --- |
| QUEUED / FAILED_RETRYABLE | 原子转 PAUSED（同事务 RUN_PAUSED）；claimNext 不领取 PAUSED，自动重试定时不再领取 | requestResume：PAUSED→QUEUED（同事务 RUN_RESUMED，清除意图），保留 retry_count 与额度；QUEUED/RUNNING 无新意图时幂等返回当前运行，不二次记事件 |
| RUNNING | 只落 `pause_requested_at`（RUN_PAUSE_REQUESTED），状态仍 RUNNING；由 worker 在动作边界确认 | RUNNING 带意图时 requestResume 明确拒绝（AGENT_RUN_NOT_RESUMABLE，"正在暂停"不允许抢先恢复） |
| PAUSED | 幂等返回，无重复事件 | 幂等 resume（见上） |
| WAITING_FOR_APPROVAL / WAITING_FOR_USER_INPUT | requestPause 抛 AGENT_RUN_NOT_PAUSABLE | 不冒充 resume，走原审批/`/continue` 入口 |
| 终态 | 抛 AGENT_RUN_NOT_PAUSABLE（竞争中返回最新状态），不改终态 | 抛 AGENT_RUN_NOT_RESUMABLE，不复活 |
| PAUSED 取消 | — | requestCancel 接受 PAUSED → CANCELED；取消后不可再恢复（终态保护回归覆盖） |

## 3. 原子边界与在途工作

### 3.1 暂停意图 ≠ 已暂停

- RUNNING 的暂停意图与"新动作准入"用**同一运行行锁**确定顺序：
  - `beginModelCall`（每次实际出站模型请求的持久化身份）事务内 `FOR UPDATE` 检查 `pause_requested_at` 与 `status='RUNNING'`：意图先落库 → 抛 `AGENT_RUN_PAUSED`，协调器捕获后转入 PAUSED（身份行未创建，无孤儿）；身份先落库 → 该请求允许完成当前阶段。短事务，不在行锁内等待模型响应。
  - 业务受理边界（`AgentPlanningOperationService.mutate`、`AgentApprovalService.proposeOrRevise`）在既有运行行锁（`FOR UPDATE` / `AgentLeaseScope.verify`）内检查暂停意图：意图先落库则抛 `AGENT_RUN_PAUSED` 不受理；受理先提交则原事务完成并保留结果。
- 真正进入 PAUSED（`AgentRunEventRecorder.recordPaused`，worker 在动作边界调用）：`AgentLeaseScope.verify` → `accountActiveTime` 结清本 claim 已确认执行段（R1 语义）并清空锚点 → 释放租约（旧 claim/旧 worker 失效）→ CAS `WHERE status='RUNNING'`。**不调用 `markBatchHandled`**：未消费 MODEL_TURN 与 PENDING invocation 全部保留；完成与暂停竞争时完成先落库则优雅返回，不把已完成的运行改成 PAUSED。
- 暂停等待与 PAUSED 期间不计入 `active_elapsed_ms`（锚点清空、claim 为 NULL）；继续不清零已确认时长；沿用 R1 的 last_progress_at 语义、既有 deadline 与限额，未新增暂停专属超时。

### 3.2 每类新动作的暂停控制点（复用 `AgentRepository.pauseIfRequested` 单一判断+收口）

| 入口 | 控制点 |
| --- | --- |
| 协调器 advance 开始（1b） | 意图已落库 → 确认 PAUSED，不推进 |
| 摘要请求前（7a） | 同上；已发出的摘要请求允许完成并保存 |
| 主模型请求 | `beginModelCall` 持久化准入边界（见 3.1） |
| 响应已保存、响应含工具调用（`afterResponseSaved`，正常与恢复共用） | 意图先落库 → 不执行调用，保存进度进 PAUSED，invocation 保持 PENDING（批次不置 batchHandled，恢复经 `pendingModelTurn` 按原身份继续）；普通最终文本不启动新外部动作，允许正常收口，不丢答案 |
| 工具批次（`executeToolBatch`） | 每个任务在线程池/限流等待之后、实际调用之前复核意图与 claim：未获准入的调用以 `AGENT_RUN_PAUSED` 哨兵返回，保持 PENDING、不误标 SKIPPED/失败、不占额度；已准入调用完成并落结果；结清后 recordPaused |
| 写/提案路径（`executeCalls` 写分支） | proposeOrRevise 前检查意图（调用保持 PENDING）；受理边界内部的 AGENT_RUN_PAUSED 拒绝被转入 PAUSED，不误标失败、不触发取消 |
| 自动重试 | PAUSED 不进 claim，重试定时/审批回写不会把它拉回 RUNNING（claimNext 候选不含 PAUSED） |
| 恢复后的下一轮 | 先复用已保存响应/已完成 invocation/已受理 operation（R2 的 5b + afterResponseSaved 共用规则），才考虑新模型请求；下一请求按当前 AGENT 配置解析 |

暂停不被当作模型错误/工具失败/超时/取消原因：哨兵与受理拒绝都转为 PAUSED 收口，不消耗恢复次数（FORMAT_REPAIR/TOOL_RETRY/MODEL_RETRY 均不动），不把未启动调用标 FAILED/SKIPPED。

### 3.3 输入分流（后端统一入口 `AgentRunService.submit`）

- 权威状态为 PAUSED 或暂停等待（非终态且 `pause_requested_at` 非空）时，先于 createRun 分流：
  - 明确续跑表达（`ResumeIntentRecognizer` RESUME）→ 调用幂等 `requestResume`，返回同一运行；**不写 USER 目标消息、不更新 latestRequest/goalRevision/有效约束**；
  - 歧义（含"继续"但带新要求/询问）→ `AGENT_RUN_PAUSED_INPUT` 明确澄清提示，保留输入；
  - 否定/普通新内容 → `AGENT_RUN_PAUSED_INPUT` 引导（继续原任务 / 先结束本次运行 / 新建会话），不静默新建任务改写当前工作状态；
  - 暂停等待期任何输入 → `AGENT_RUN_PAUSE_PENDING`（不能提前撤销暂停或另起推进者）。
- 前端从暂停界面发送时显式携带 `pausedRunId`（SubmitAgentMessageRequest 新增可选字段）；与权威运行不符时明确拒绝（运行已切换）。后端识别不依赖该字段。
- 已恢复/运行中的重复续跑表达：幂等返回当前运行，不退化为普通 submit 新建任务；WAITING_FOR_APPROVAL/USER_INPUT 与 FAILED_RETRYABLE 的文本仍按原用途处理（澄清回复走 `/continue`、重试用既有入口），不冒充 resume。
- 识别器（`ResumeIntentRecognizer`，纯函数、三值、无模型请求/关键词库平台）：整句匹配"继续/接着/恢复 + 允许修饰字符集"或"把剩下的做完"句式才算 RESUME；子串命中不算；否定→UNRELATED；疑问/带新要求→AMBIGUOUS；礼貌前缀、空白、标点已归一。

## 4. API 与前端

- `POST /runs/{runId}/pause`、`POST /runs/{runId}/resume`（沿用项目访问授权），返回权威运行详情（`AgentRunDetailView`/`AgentRunDetailResponse` 新增 `pauseRequestedAt`）。页面无继续按钮；resume 是内部控制端点，由提交入口分流调用。
- 前端（沿用既有视觉与职责：workspace 编排、纯归约、视图组件）：
  - QUEUED/RUNNING/FAILED_RETRYABLE 显示"暂停"（loading+防重复）；"停止运行"更名"结束本次运行"作次要操作。
  - "正在暂停"（RUNNING+pauseRequestedAt）与"已暂停"（PAUSED）两个横幅区分等待与确认；PAUSED 横幅提示"输入'继续'，接着完成当前任务"，输入框占位符同步。
  - 事件归约：RUN_PAUSE_REQUESTED 仅标记 pauseRequestedAt（不改 RUNNING）、RUN_PAUSED→PAUSED、RUN_RESUMED→QUEUED 并清除标记；活动流渲染"已暂停，进度已保留"/"已继续"控制行，不伪造用户任务或最终回答。
  - PAUSED 停止执行流无意义重连（SSE 停止显示为正常暂停状态，非故障警报）；恢复经 send 同 run 分支重新订阅原 run，不重建时间线、最终回答只出现一次、工具条目不重复。
  - pause 请求记录作用域（projectId/sessionId/runId/restoreSeq），迟到响应不覆盖已切换视图；失败/歧义时保留草稿。
- 键盘/可达性沿用现状：textarea 原生可聚焦、发送按钮标准绑定；Ctrl+Enter 为原生 Vue 键位绑定。**更正**：首轮报告中"Ctrl+Enter 处理器为原生 Vue 键位绑定（单元测试覆盖）"的说法不完整——非澄清分支当时写的是 `send` 函数引用而非 `send()` 调用，按键确实无发送请求；该缺陷已由组件测试复现并在"五项限定补修"（第 10 节 F3）中修复，与 bsk 合成事件限制无关。

## 5. 确定性回归（真实 PostgreSQL，Testcontainers `pgvector/pgvector:pg17`，无真实模型）

新增 `AgentPauseResumePostgresTest`（12 项，全过），关键故障注入均为确定性同步点（意图直写、替身模型在已知时刻落意图、单许可调度器强制排队、租约时钟直接改库），不 sleep 等竞态：

1. QUEUED 暂停/继续：PAUSED 不被 claim；重复 pause/resume 幂等且事件各恰一次；原 runId 可再调度。
2. FAILED_RETRYABLE 暂停：重试到期不领取 PAUSED；恢复保留 retry_count=1 且可立即调度。
3. 模型在途暂停：响应保存（MODEL_TURN 一次）、工具保持 PENDING 不 SKIPPED、批次未消费；恢复零新增模型调用、只执行剩余调用。
4. 完整文本已保存后暂停+worker 退出：接管只确认 PAUSED；**输入预算已用满（input_tokens_used=max）时恢复经真实 `AgentWorker.process` 消费已保存结果**（预算前置检查区分"准入下一次请求"与"消费已保存结果"），零新增请求、FINAL_ANSWER/消息/RUN_SUCCEEDED 恰一次。
5. 部分工具已完成（12 已用+4 批次完成 2 项）：接管只确认 PAUSED（2 PENDING 不 SKIPPED）；恢复只执行剩余 2 项，`tool_calls_used` 恰到 16（额度边界），已落结果调用按原 invocation 身份复用。
6. 限流排队（单许可调度器强制确定性排队）：已准入调用结果落库；排队调用未获准入保持 PENDING；恢复后按原身份执行，总计恰好 3 次执行。
7. 业务受理边界（真实 `AgentPlanningOperationService` + 真实 ai_task_plan/attempt 行）：暂停意图先落库 → mutate 抛 AGENT_RUN_PAUSED、`commands.create` 零调用、调用保持 PENDING；清除意图后受理一次；"业务已提交、工具结果未落库"窗口的接管恢复复用同一 operation，`create` 恰一次（零重复受理）。
8. 终态/等待态拒绝暂停与恢复；PAUSED+取消后不可复活；等待澄清不提供暂停。
9. 控制事务中途失败（真实事件服务 + TransactionTemplate 回滚）：状态/意图/RUN_PAUSED 事件整体回滚，无拆分提交；回滚后命令可重执行。
10. 暂停→确认→恢复→新请求：`resolveRequest`/`callModel` 各恰一次（下一请求按当前配置解析）。
11. 提交入口分流：PAUSED 下"继续"恢复同一 run（不新建、不加 USER 消息、goalRevision 不变）；"继续刚才的任务"幂等；"如何继续/不继续了/继续检查另一个项目的风险"均拒绝且不新建；`pausedRunId` 绑定不符拒绝；暂停等待期"继续"被 AGENT_RUN_PAUSE_PENDING 拒绝。
12. 旧 claim 拦截：接管后旧 worker 的 recordPaused 被租约拒绝，新 claim 收口 PAUSED。

意图识别单元测试 `ResumeIntentRecognizerTest`（5 项）：多种明确续跑说法（含礼貌/空白/标点）RESUME；否定 UNRELATED；询问与带新要求 AMBIGUOUS；普通内容 UNRELATED。

**先锁定失败再修复**：开发过程中上述用例曾在两个真实缺陷上失败并随后修复——(a) `beginModelCall` 无持久化准入边界时无法保证"意图先落库则不发新请求"的原子顺序；(b) Worker 预算前置检查会把"输入预算用满+有待消费结果"的恢复丢成 BUDGET_EXCEEDED（用例 4 固定该行为）。第三方行为（如合成 peer 的空回复）未作为产品缺陷处理（见 8.2）。

## 6. 既有回归与全量

- 受影响回归（全部实际执行通过，共 192 项）：`AgentCrashRecoveryTimingPostgresTest` 5、`PersistedModelTurnRecoveryPostgresTest` 23、`AgentRepositoryIntegrationTest` 39、`AgentRuntimeCoordinatorTest` 32、`AgentRuntimeBehaviorTest` 25、`CrossTickToolCallTest` 7、`AgentRuntimeRequestSnapshotTest` 3、`AgentConvergencePolicyTest` 8、`AgentLoopGuardTest` 13、`AgentWriteProposalSpringIntegrationTest` 2、`AgentModelConfigurationSwitchPostgresIntegrationTest` 9、`AgentMigrationIntegrationTest` 6、`AgentMigrationChecksumTest` 1、`NextStageMigrationPostgresTest` 1、`LegacyApprovalCitationUpgradeTest` 1、`Phase08MigrationSafetyIntegrationTest` 6、`TaskPlanSpringBeanPostgresIntegrationTest` 16。迁移断言已随 V61 对齐（7→8、15→16、版本列表追加 61、最新版本 61）。
- 后端全量（mvn -o test，一次通过）：**1134 项，失败 0，错误 0，显式跳过 11**，BUILD SUCCESS。11 项跳过与既有批次完全一致（BrowserAcceptanceHostTest、ExistingDataUpgradeRehearsalTest、RealAcceptanceHostTest、RealRetrievalBaselineTest、ReliabilityAcceptanceHostTest、ReliabilityOperationDatabaseTest×4、OllamaEmbeddingSmokeTest、OpenCodeZenSmokeTest），未新增跳过。日志：`docs/acceptance-evidence/2026-10-06/pause-resume/backend-full-pause-resume-20261006.log`。
- 前端：vitest **38 文件 172 项全过**（含新增 `agent-pause-resume.test.ts` 纯归约 6 项、`AgentView.pause-resume.test.ts` 组件 4 项），`vue-tsc --noEmit` 通过。
- 注：全量运行于 peer 诊断行（仅影响显式跳过的 BrowserAcceptanceHostTest 的测试设施）改动之前；该改动不触及生产代码。

## 7. 隔离浏览器验收（browser-skill/bsk，用户暂停于最后一轮合并检查前）

隔离设施：离线副本 PG `ai-collab-acceptance-postgres-20261003`（127.0.0.1:55432，验收后已停止）、`BrowserAcceptanceHostTest` host（18080 + Testcontainers Redis/MinIO + 本地合成 peer）、vite（15173），隔离项目 `PauseResume Acceptance 20261006`。所有模型响应合成。证据目录：`docs/acceptance-evidence/2026-10-06/pause-resume/`（host 日志×2、vite 日志、shots/01–08）。

| 场景 | 结果与证据 |
| --- | --- |
| 模型在途暂停→已暂停→刷新→输入继续 | 通过。SLOW 在途点击"暂停"→"正在暂停"横幅（01）；DB：PAUSED、goal 不变、list_tasks PENDING（02）；刷新后真实状态保留、SSE 无意义重连停止（03）；输入"继续"→同 run 恢复，事件链 RUN_PAUSE_REQUESTED→RUN_PAUSED→RUN_RESUMED→RUN_SUCCEEDED 各恰一次，步骤/答案无重复（04） |
| 歧义/否定不误恢复、草稿保留 | 通过。"继续检查另一个项目的风险"/"不继续了"均不新建运行、不恢复、草稿保留（05）；"继续"恢复完成 |
| 暂停期间切换模型 A→B | 通过。A 在途暂停→切换默认 provider→UI 输入"继续"→peer 日志显示恢复后的工具轮与收尾轮均为 `model=acceptance-b`，运行 SUCCEEDED，模型芯片显示 acceptance-b，"已继续"行正常（07） |
| 切换会话/迟到控制响应不串状态 | 通过。暂停/恢复活动期间切换会话视图无横幅泄漏，切回刷新显示真实状态；作用域核对逻辑由组件测试（AgentView.pause-resume.test.ts）与 use-agent-workspace 的 scope/restoreSeq 门闩覆盖 |
| 窄屏 | 基本通过。390×844 下无横向溢出（`scrollWidth<=clientWidth`=true），暂停状态/横幅/输入可用（08） |
| 已受理规划继续完成（浏览器） | **未在浏览器完成**：host 以 `planning.enabled=false` 运行，规划生成链路未启用；该行为由 PG 回归用例 7（受理边界 + 同一 operation 零重复受理）与规划子系统既有机制（操作状态由 attempt 持久状态派生，暂停不触碰规划任务）覆盖，浏览器证据留待后续 |
| 工具展开/焦点/最终回答去重 | 通过（场景 A/B 全程展开态保持、答案一次）；专项检查沿用既有 AgentView 活动块测试 |

首轮验收中断说明：第一次场景 C 尝试的运行失败（FORMAT_REPAIR），经 peer 请求级诊断定位为**合成替身局限**——目标"检查本周风险"命中的 Skill 暴露的工具集不含 `list_tasks`，替身只会为 list_tasks 类请求产出工具调用，其余返回空内容触发格式修复；非产品缺陷（runs 1–3 与重做的场景 C 均成功）。诊断输出行已加入验收替身（`ScriptedAcceptanceModel`，仅测试设施）。

## 8. 未验收边界与说明

1. 浏览器验收未走完最后一轮合并检查（桌面+窄屏全景复扫、后台规划完成的页面证据），应用户要求暂停；已完成的场景与截图如上，`bsk`/host/前端/隔离 PG 均已清理停止，未删任何既有容器。
2. Ctrl+Enter：首轮把按键无反应归因于 bsk 合成键盘事件限制，是错误归因——真实缺陷是模板绑定的非澄清分支只返回 `send` 函数引用未执行（F3，见第 10 节），已由真实 Vue 编译的组件键盘事件回归修复并覆盖。bsk 合成事件的限制本身仍然存在，但不再是该现象的解释。
3. 后端全量运行于 peer 诊断行改动之前（该改动仅影响显式 opt-in 跳过的测试设施，不影响生产代码与其它测试）。
4. 真实模型语义质量、24 轮长对话、精确计费、逐字流式：仍为独立未验收项。
5. 暂停语义的已知粒度：RUNNING 暂停确认依赖 worker 到达动作边界（在途模型请求/工具允许完成当前阶段）；未提供强制立即中断。

## 9. 继承修改与本轮修改

- 继承未提交（本轮未改语义）：M1–M4 装配/投影/目录归属、R1/R2 全部修复与回归、V59/V60、前端 modules/agent 既有交互等，见 `docs/agent-runtime-maintenance.md`。
- 本轮新增/修改：迁移 V61；领域（AgentRunStatus/AgentEventType/AgentStateMachine）；ErrorCode 6 项；AgentRunEventRecorder（requestPause/requestResume/recordPaused/ownsEvent/beginModelCall 准入/事件重载）；AgentRepository（暂停查询与委托、requestCancel 收编 PAUSED、pauseIfRequested）；AgentWorker（暂停优先 + 预算前置区分）；AgentRuntimeCoordinator（1b/7a 门、beginModelCall 准入捕获、afterResponseSaved 工具门）；AgentToolCallExecutor（哨兵/BatchOutcome/任务级复核/写路径门）；AgentPlanningOperationService 与 AgentApprovalService（受理边界检查）；AgentRunService（pause/resume 用例、submit 分流）；ResumeIntentRecognizer（新）；Controller 2 端点；SubmitAgentMessageRequest/AgentRunDetailView/AgentRunDetailResponse 新字段；前端 8 文件 + 2 测试文件；回归 2 套（12+5）；迁移断言对齐 4 处；报告与维护文档。

## 10. 五项限定补修（2026-10-06 第二轮）

依据审查（`agent-pause-resume-review-20261006.md`）与复现证据（`docs/acceptance-evidence/2026-10-06/pause-resume-review/`），本轮只修五项已复现缺陷；复现探针转为正式回归后归档保留，未进入测试目录。编排、恢复设计、R1/R2 与继续按钮约定均未改动；全部改动保留未提交。

### F1 迟到绑定续跑输入不再退化为普通新任务（`AgentRunService.submit`）

- **修复前**：`pausedRunId` 仅在绑定不符当前运行时拒绝；控制分流整体受 `!terminal()` 限制。原运行恢复后快速完成/取消，迟到或重复的"继续"带着原 runId 到达会跳过分流、落入 `createRun`，生成第二个目标为"继续"的运行和 USER 消息（真实 PG 复现：运行数 1→2）。
- **修复后**：显式绑定（`pausedRunId` 非空）的输入始终留在该运行的控制作用域：绑定与权威运行不符（不存在/不属于当前会话/已切换，含会话无任何运行）按既有 `AGENT_RUN_NOT_FOUND` 拒绝；运行已终态时，明确续跑表达幂等返回真实终态（前端展示真实状态，不当作恢复成功继续调度），非续跑内容抛 `AGENT_RUN_NOT_RESUMABLE` 并保留输入。未绑定控制作用域的真正新任务仍按普通提交。
- **设计决策**：终态续跑选择"返回真实状态"而非报错——与 requestResume 的幂等语义一致，前端同 run 分支只会刷新真实状态、不会重复调度（终态不重连）。等待审批/澄清与 FAILED_RETRYABLE 的文本仍按原用途处理（不冒充 resume），本轮未改。
- **C1 补全（同日复核）**：仅拦终态不够——运行恢复后进入等待澄清、等待审批、重试等待，或排队/运行中收到绑定非续跑文本，仍会绕过全部分支落入 createRun（隔离 PG 复现：1→2 个运行）。补全后兜底规则为"所有未获合法控制分支处理的绑定输入一律不进入普通提交"，按权威状态给出明确业务提示（澄清走原 /continue、重试走既有入口、排队/运行中非续跑文本引导先结束本次运行或新建会话），输入保留；终态绑定"继续"幂等返回真实状态不变。回归：`boundInputOnActiveRunNeverFallsIntoCreateRun`。

### F2 输入续跑响应的页面作用域保护（`use-agent-workspace.ts` send/loadMessages）

- **修复前**：`pauseActiveRun` 有作用域核对，但经 `send` 的续跑响应在 await 后无条件应用：切会话后迟到响应会清空新会话草稿、重建时间线、重启当前订阅并触发未绑定作用域的 `loadMessages`。
- **修复后**：发送时捕获 projectId/sessionId/restoreSeq/绑定 runId，响应成功与失败都先做作用域核对（含组件卸载标志 disposed）；过期的成功/失败响应不修改新页面、不清草稿、不换订阅、不显示旧请求错误。同 run 恢复分支中，发送后新增的草稿（与发送内容不同）不再被误清空；`loadMessages` 捕获调用时的项目/会话并在返回时核对，延迟消息加载不覆盖新会话；新任务分支在消息加载后二次核对，不再把订阅强加给已切换页面。
- **设计决策**：局部作用域闭包 + loadMessages 内的返回核对，未建通用请求管理框架。切项目场景由 restoreSeq 变化（项目切换会触发 load/restoreSession 递增代次）走同一守卫分支，与切会话共用核对路径。
- **C2 补全（同日复核）**：loadMessages 此前只核对项目/会话 ID——续跑后的消息加载挂起、用户 A→B→A 切回时 ID 相同，旧加载结果会覆盖重新恢复后的最新消息（组件复现）。补全后 loadMessages 捕获调用时的恢复代次并在返回时连同 disposed 一起核对；send 闭包改用"预期代次"（新任务分支自己递增 restoreSeq 后同步更新），不再永久豁免代次，消息加载后启动订阅前也经同一核对。回归：新增"A→B→A 切回后挂起的旧消息加载不得覆盖重新恢复的最新消息"。

### F3 Ctrl+Enter 真实调用修正（`AgentView.vue`）

- **修复前**：`@keydown.ctrl.enter.prevent` 表达式为 `... ? continueRunHandler() : send`——非澄清分支只返回函数引用，未执行；按键无任何请求。首轮报告误归因于 bsk 合成事件限制（见第 4/8.2 节更正）。
- **修复后**：非澄清分支调用 `send()`；普通发送、PAUSED 输入续跑走 send，WAITING_FOR_USER_INPUT 走 continueRunHandler，防重复与点击按钮一致（send/continueRunHandler 内部守卫不变）。

### F4 同一运行旧控制回包不覆盖较新事件（`pauseActiveRun` + 续跑响应复核）

- **修复前**：项目/会话/run/代次都未变化时，暂停响应快照无条件替换 `timeline.run`；RUN_PAUSE_REQUESTED→RUN_SUCCEEDED 已应用后，旧 RUNNING 快照仍会把已完成页退回"正在暂停"，且事件流结束后不再纠正。
- **修复后**：作用域核对通过后，再以 `detail.lastEventSequence` 与 `timeline.lastSequence`（已应用事件地平线）比较：快照序号落后即整体跳过——不覆盖状态/暂停标记、不执行 PAUSED 确认分支的停流与消息加载（订阅决定不被旧回包替代）。暂停/恢复/完成的状态迁移都伴随事件序号，序号核对覆盖非终态之间的顺序，不用"终态永不回退"硬编码。输入续跑的同 run 响应无序号可依，采用局部可解释核对：仅当当前视图仍处于发出时的 PAUSED 状态才应用响应与重订阅，事件已推进（RUN_RESUMED 及之后）时不回退。
- **保留语义**：工具展开、时间线与回答去重逻辑未动；正常最新的控制响应仍生效（序号不落后即应用）。

### F5 摘要首次请求与重压缩补齐持久化暂停准入（`AgentRunEventRecorder`/`AgentContextSummarizer`）

- **修复前**：主请求 `beginModelCall` 有运行行锁准入，但摘要经 `beginSummaryAttempt`/`beginSummaryRecompressAttempt` 的独立身份路径直接建身份并调模型；首次摘要在途时提交暂停意图，返回超长输出后仍会新发重压缩请求（PG 探针计数 2，期望 1）。
- **修复后**：两个 begin 方法在身份创建事务内先复用 `AgentLeaseScope.verify`（租约/取消）再 `FOR UPDATE` 检查 `pause_requested_at` 与 `status='RUNNING'`，与 requestPause 行锁串行化；意图先落库则不创建请求身份、不发模型请求（抛 `AGENT_RUN_PAUSED`）。首次摘要的暂停拒绝在 maybeSummarize 中按控制结果处理（debug 记录、按无摘要路径返回），不经通用失败结算；重压缩的暂停/状态拒绝将首次已发生用量照常结算（outcome=PAUSED/CANCELED + RECOMPRESS_SKIPPED_* 备注）、沿用上一份有效摘要、不虚报覆盖，且不消耗重试或触发主请求继续出站——主请求由 `beginModelCall` 同一边界拒绝后经协调器收口 PAUSED。短事务准入，锁不跨网络等待。
- **设计决策**：准入检查放在 Recorder（身份创建处）而非流程开头，保证"意图先落库 → 无身份无请求"的持久化顺序；不合并两次实际请求的独立结算身份；过期 claim 由租约校验拒绝准入。随契约收紧，两处既有测试的摘要调用点按生产状态对齐为 RUNNING（`AgentRepositoryIntegrationTest` 两例）。

### 正式回归与验证

- 后端（真实 PostgreSQL，Testcontainers，隔离容器随测启停）：`AgentPauseResumePostgresTest` 12→18 项。新增：终态绑定输入（完成态续跑幂等返回真实状态、取消后输入不复活不新建、终态绑定非续跑内容业务拒绝、绑定失效拒绝、未绑定真正新任务正常提交；断言 run 数量/runId/USER 消息/goalRevision）；摘要暂停准入（意图先落库零请求、意图清除后正常提交；首次在途暂停仅 1 次请求、无重压缩身份、首次用量结算、不虚报 summary；真实协调器链路收口 PAUSED 且主请求 `callModel` 零调用；过期 claim 准入被租约拒绝、有效 claim 正常）；C1 补全（QUEUED 绑定非续跑文本、FAILED_RETRYABLE 迟到"继续"、WAITING_FOR_USER_INPUT 迟到"继续"、RUNNING 绑定非续跑文本均不新建，工作状态不被控制输入推进）。
- 后端受影响回归：五项补修轮共 **222 项 0 失败**（AgentPauseResumePostgresTest 17、AgentContextSummarizerTest 24、AgentRepositoryIntegrationTest 39、AgentActualTokenUsageIntegrationTest 19、AgentRuntimeCoordinatorTest 32、AgentRuntimeBehaviorTest 25、AgentCrashRecoveryTimingPostgresTest 5、PersistedModelTurnRecoveryPostgresTest 23、CrossTickToolCallTest 7、AgentWriteProposalSpringIntegrationTest 2、ResumeIntentRecognizerTest 5、AgentRuntimeRequestSnapshotTest 3、AgentToolOutputProjectorTest 5、AgentModelConfigurationSwitchPostgresIntegrationTest 9、AgentMigrationIntegrationTest 6、AgentMigrationChecksumTest 1）；C1/C2 补全轮按影响复跑核心套件 **138 项 0 失败**（PG 18、摘要 24、仓库 39、协调器 32、行为 25）。改动范围有限，按任务书约定未重跑 1134 项全量；历史全量数字沿用第 6 节证据，不代表本轮执行。
- 前端：vitest 全量 **38 文件 183 项全过**（含 `AgentView.pause-resume.test.ts` 4→15 项），`vue-tsc --noEmit` 通过。新增回归：Ctrl+Enter 三条键盘路由（普通/暂停/等待澄清各恰一次、调用正确 API）；切会话保留 B 草稿与订阅、切回原会话（恢复代次变化）响应不生效、旧请求失败不显示错误、延迟消息加载不覆盖新会话、A→B→A 切回后挂起的旧消息加载不覆盖重新恢复的最新消息；同 run 旧回包三类（RUN_SUCCEEDED 后旧暂停回包、RUN_PAUSED 后旧 RUNNING 快照、RUN_RESUMED 后旧 PAUSED 快照——状态不回退、不误停事件流）。
- 复现探针（`PauseResumeReviewProbe.java`、`AgentPauseResume.review-probe.test.ts`，以及 C1/C2 复核轮的 `PauseResumeClosureProbe.java`、`AgentPauseResume.closure-probe.test.ts` 及各日志）按原样归档于 `docs/acceptance-evidence/2026-10-06/pause-resume-review/`，保留当时的失败证据；未复制进测试目录。
- 浏览器：本轮未启动（任务书不要求补全景复扫）；F3 已由真实 Vue 编译的组件键盘事件回归证明，不再依赖浏览器补测。

### 资源与提交状态

本轮创建的临时诊断与日志已清理；Testcontainers 容器由测试框架自动回收，无遗留容器/进程；未触业务数据库、真实模型与业务服务。全部改动继续保留未提交，未推送、未合并 main、未部署。
