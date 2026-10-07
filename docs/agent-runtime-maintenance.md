# Agent 运行时维护约定与核心调用图

日期：2026-10-05。对应维护批次：M1–M4（分支 `codex/context-foundation`，基线 `d5f09ab` + 本轮工作区改动）。本文是接手者日常修改 Agent 核心时的入口索引与边界约定；设计依据见 `docs/agent-maintainability-and-course-review-20261005.md`。

## 1. 目录职责约定

| 位置 | 负责什么 | 新增代码的边界 |
| --- | --- | --- |
| `agent/api` | HTTP/SSE 与请求响应转换 | 不承担执行循环或直接拼数据库操作 |
| `agent/application` | 会话、运行、审批、规划受理等用例；生产装配入口 `AgentRuntimeConfiguration` | 调用已有业务应用服务，不让模型参数决定权限 |
| `agent/application/runtime` | 本轮请求准备、上下文组装、模型/工具推进、执行控制（含 `AgentLoopGuard`、`AgentConvergencePolicy`） | 各职责有唯一入口；不重复解析同一请求的配置 |
| `agent/domain` | 业务语义、状态规则、工具与 Skill 契约 | **不导入 application/api/infrastructure** |
| `agent/infrastructure` | SQL 持久化、工具对业务服务的适配、外部协议 | 维持原子写入与调用身份，不隐含新的调度循环 |
| 前端 `modules/agent` | 工作区编排、纯归约（activity/conversation）、可视组件 | timeline 为运行视图来源；订阅统一管理 |

## 2. 生产装配（M1 后）

`AgentRuntimeConfiguration`（`agent/application`）是 Agent 运行时的生产装配入口：

- `AgentModelMessageComposer`（消息组装器）与 `AgentToolCallExecutor`（工具执行器）在此注册为容器单例；
- `AgentContextSummarizer`（摘要器）是 `@Component` 单例；
- `AgentRuntimeCoordinator` 的主构造函数显式接收这三个协作者，**不再自行 `new`**；
- `AgentToolCallExecutor` 构造时接收 `AgentToolScheduler`（专用线程池 + 用户/运行信号量限流），没有 commonPool 默认值；
- `RoutingAgentModelExecutor` 构造时接收 `AgentModelConfigurationStore`（唯一配置来源），没有后置 setter，也没有 store 为 null 时的旁路解析。

单元测试用协调器的两个标注"测试便捷装配"的构造函数（内部显式构造三个协作者，依赖全部来自测试传入的受控对象）；需要自定义 `AgentContextProperties` 或逐个替换协作者时，直接用主构造函数（参考 `AgentRuntimeCoordinatorTest.coordinatorWithLargeToolDefinition`）。装配回归：`AgentWriteProposalSpringIntegrationTest.productionAssemblyInjectsContainerCollaboratorsWithoutDuplicates`。

## 3. 核心调用图

```
AgentRuntimeJob（内嵌 worker，租约 claimNext）
└─ AgentWorker → AgentRuntimeCoordinator.advance(run)          [编排与收尾，无 SQL 拼接]
   ├─ AgentCancellationService.throwIfRequested                [取消检查]
   ├─ AgentContextAssembler.assemble                           [可信上下文：角色/页面/提案]
   ├─ AgentContextBudget + AiRequestDeadline                   [运行时长/窗口预算]
   ├─ AgentSkillRegistry.select → AgentPlanService.ensurePlan
   ├─ repository.pendingModelTurn → AgentToolCallExecutor.executeCalls(recovery=true)
   │                                             [恢复轮次按持久化 source_mode 校验]
   ├─ AgentConvergencePolicy.decide                            [继续/收尾/耗尽]
   ├─ modelExecutor.resolveRequest(run)                        ★每请求一次
   │   └─ AgentModelConfigurationStore.require                 [租约校验+快照落库，FOR UPDATE]
   ├─ AgentContextBudget.perRequest(窗口, 运行剩余, 单次上限)
   ├─ AgentModelMessageComposer.composeV2 / buildMessageHistory
   │   ├─ repository.workingState / listRecentMessages          [状态与历史查询]
   │   ├─ staleAwareOutput                                      [citations 失效判定，DB 边界]
   │   └─ AgentToolOutputProjector.projectToolOutput            ★纯函数：列表/规划/正文/通用投影
   ├─ AgentContextSummarizer.maybeSummarize                     [有界增量摘要，CAS，单独记账]
   ├─ repository.beginModelCall → modelExecutor.callModel(resolved)
   │   ├─ NativeToolCallingExecutor                             [NATIVE_TOOLS]
   │   └─ LegacyReadOnlyAgentExecutor                           [CHAT-only，只读]
   ├─ repository.recordModelTurnWithSettlement                  ★原子：状态+步骤+事件+用量结算
   ├─ AgentToolCallExecutor.executeCalls                        [工具轮次]
   │   ├─ 校验：白名单/权限/Schema/循环(AgentLoopGuard)/Legacy 写禁用
   │   ├─ 已完成结果复用 knownInvocationResult；澄清/提案/受控规划批约束
   │   ├─ 只读并行（AgentToolScheduler.executor + 限流）、写/审批串行
   │   ├─ AgentApprovalService.proposeOrRevise                  [审批提案]
   │   └─ repository.recordToolResult                           [逐条原子落结果+事件]
   └─ 终态：recordFinal / recordBudgetExceeded / recordWaitingForInput …
```

★ 标注的三处是最容易误改的契约点：

1. **配置快照**：`resolveRequest` 每次请求只解析一次，`ResolvedRequest` 贯穿窗口预算、提示词模式、工具暴露、出站调用与本响应的工具校验；配置变更只影响下一次请求。不要在组装或校验里再读配置。
2. **工具投影**：`AgentToolOutputProjector` 不访问数据库、不读配置、不决定下一步；列表页的"可见记录↔计数↔nextCursor 一致"契约（`ListPageContract`）在这里维持，阈值与算法不要在组装器里复刻一份。
3. **原子写入**：状态、步骤、消息、事件与用量的写入在 `AgentRepository`/`AgentRunEventRecorder` 的同一事务内完成（`recordModelTurnWithSettlement`、`recordToolResult`、`recordBudgetExceededWithSettlement`）；不要为拆小文件把这些拆成多个相互调用的事务 Bean。

## 4. 修改某类需求时改哪里

| 需求 | 入口 |
| --- | --- |
| 调整单次请求预算/窗口 | `AgentContextProperties` + `AgentContextBudget` |
| 改提示词（公共规则/Skill 模板） | `AgentModelMessageComposer.buildSystemPrompt`（公共）；`agent/domain/model/builtin/*Skill`（任务要求） |
| 改工具结果的模型可见视图/分页契约 | `AgentToolOutputProjector`（投影）；`ListPageContract` + `list_tasks`/`list_milestones`（服务端分页） |
| 改工具暴露/执行校验/审批 | `AgentToolCallExecutor`；权限策略在 `AgentToolRegistry.checkPolicy` 与 `domain/policy/AgentToolPolicy` |
| 改摘要触发/覆盖/CAS | `AgentContextSummarizer` |
| 改模型路由/能力判定 | `RoutingAgentModelExecutor`（配置来源：`AgentModelConfigurationStore`） |
| 改执行预算收敛/收尾 | `AgentConvergencePolicy`；已持久化结果的复用与收尾判定在 `AgentRuntimeCoordinator.completeTextTurn`（正常与恢复共用） |
| 改重复调用检测 | `AgentLoopGuard` |
| 改崩溃接管计时 | `AgentRepository.claimNext`（已确认执行区间）+ `AgentRunEventRecorder.markProgress/accountActiveTime`（进度锚点 `agent_run.last_progress_at`，见 `V60__agent_last_progress_at.sql`） |
| 改已持久化模型结果恢复 | `AgentRepository.pendingModelTurn`（未消费轮次）+ 协调器 5b 恢复分支（先于 `decide`，经 `afterResponseSaved` 共用分派）；恢复批次经 `executeCalls(recoveryBatch=true)` 按持久化 source_mode 校验；消费标记与终态同事务（`recordFinal(…,true)`/`recordWaitingForInput`/`recordBudgetPartialAnswer`）；强制收尾意图随响应持久化（`ModelTurnResult.finalizing` + `recordModelTurnWithSettlement(…,finalizingIntent)`），恢复经 `persistedFinalizing` 读取、历史缺元数据时按核心动作证据保守回退 |
| 改主动暂停/继续语义 | 见第 8 节：意图写路径 `AgentRunEventRecorder.requestPause/requestResume/recordPaused`；动作边界检查点复用 `AgentRepository.pauseIfRequested`；模型请求持久化准入边界在 `beginModelCall`，摘要出站请求准入边界在 `beginSummaryAttempt`/`beginSummaryRecompressAttempt`；业务受理边界在 `AgentPlanningOperationService.mutate` 与 `AgentApprovalService.proposeOrRevise`；输入续跑分流在 `AgentRunService.submit` + `ResumeIntentRecognizer`；迁移 `V61__agent_pause_resume.sql` |

## 5. 本轮（M1–M4）落地的结构变化

- **M1**：协调器主构造函数接收组装器/工具执行器/摘要器；组装器与工具执行器注册为 `AgentRuntimeConfiguration` Bean；工具执行器构造时接收 `AgentToolScheduler`；`RoutingAgentModelExecutor` 构造时接收 `AgentModelConfigurationStore` 并删除 `userProviders` 直连旁路、`configureStore`/`configureToolScheduler` 后置注入与 `isLegacyMode(userId)` 无消费者方法。
- **M2**：`AgentToolOutputProjector` 从组装器抽出（列表页/规划/正文/通用投影 + 页契约），组装器保留内容挑选、过期判定与消息配对；纯投影测试迁至 `AgentToolOutputProjectorTest`。
- **M3**：`AgentPromptFactory` 及其 Bean、专属测试删除；`RoutingAgentModelExecutor.isNativeToolError` 无调用私有方法删除。Legacy/CHAT-only 能力与旧记录 source_mode 恢复兜底保留（`isLegacyModeForRun` 仅用于恢复路径）。
- **M4**：`AgentLoopGuard`、`AgentConvergencePolicy` 从 `agent/domain/policy` 移入 `agent/application/runtime`，domain 不再反向导入 application；两份 LoopGuard 测试合并保留完整版。

## 6. 本轮（R1–R2）恢复可靠性修复

完整报告：`docs/agent-recovery-reliability-report.md`（分支 `codex/context-foundation`、HEAD `d5f09ab` + 工作区改动）。

- **R1 崩溃接管计时**：`agent_run.active_elapsed_ms` 只累计**已确认执行时长**；接管区间为 `[claim_started_at, last_progress_at]`，新增迁移 `V60__agent_last_progress_at.sql` 记录进度锚点（只在模型轮次/工具结果落库事务内更新，终态结清并清空）。租约到期、离线、排队与重试等待不计入；未确认尾段不冒充执行时长。原有限额与 deadline 未改。
- **R2 已持久化结果恢复**：`pendingModelTurn` 现在也返回"无工具调用但已有正文且未消费"的轮次；协调器 5b 分支在解析模型配置之前复用该响应，并与正常路径共用同一套响应后检查与分派（`afterResponseSaved`）。结果消费标记与终态写入同事务，保证恰好一次；`[QUESTIONS]`、预算部分回答与空内容错误语义保持。
- **R2 限定补修（2026-10-06）**：复核发现恢复时重新 `decide` 会用落库后的计数改写原请求语义（EXHAUSTED 兜底覆盖完整答案、`needsFinalRequest` 收尾意图丢失、输入实际超限检查缺失）。补修后：消费已提交结果先于准入判定；本轮请求实际采用的 `finalizing` 随响应持久化（`ModelTurnResult.finalizing`，与响应/用量同事务），恢复按持久值处理、历史缺元数据时保守回退；恢复批次经 `afterResponseSaved(recoveryBatch=true)` 保留 source_mode 校验。详见报告第 10 节。回归 `PersistedModelTurnRecoveryPostgresTest` 9→16 项（新增 7 项为"正常 vs 接管"成对比较）。
- **R2 工具恢复补修（2026-10-06 第二轮）**：共享入口曾把恢复批次整批计入总工具额度（部分完成的合法批次被误判超限、剩余调用 SKIPPED），且旧元数据回退把"核心动作未发生"套到工具批次上（旧规划调用被收尾协议否决）。补修后：`validateToolBatch(run, limits, batch, alreadyCompletedCalls)` 总额度只计尚需执行的新增调用（已落结果调用数由 `AgentRepository.countSettledInvocations` 按原 invocation 身份统计），单轮数量仍按原批次校验；`persistedFinalizing` 对工具批次回退为普通轮次（显式 `finalizing=true` 仍拒绝），旧文本回退不变。详见报告第 11 节。回归 16→23 项，关键批次用真实工具执行器 + 安全替身工具验证剩余执行次数。
- 回归：`AgentCrashRecoveryTimingPostgresTest`（5）、`PersistedModelTurnRecoveryPostgresTest`（9）；两套均在临时回退修复的旧行为上失败（R1 3 失败 / R2 6 失败+3 错误），修复后通过。后端全量 1103 项、通过 1092、显式跳过 11、零失败/错误。

## 8. 主动暂停与输入续跑（2026-10-06 交付）

完整报告：`docs/agent-pause-resume-report.md`（迁移 V61、控制点清单、回归与浏览器证据、未验收边界）。核心语义：

- **状态**：`PAUSED` 为非终态。QUEUED/FAILED_RETRYABLE 暂停=原子直接转 PAUSED；RUNNING 暂停=仅落 `pause_requested_at`（RUN_PAUSE_REQUESTED，**不递增 version**），由 worker 在动作边界经 `recordPaused` 确认（结清已确认执行段、释放租约、**不 markBatchHandled**——未消费轮次与 PENDING 调用全部保留）；resume 仅 PAUSED→QUEUED（RUN_RESUMED），幂等、不清零额度/重试/进度。WAITING_* 与终态拒绝暂停/恢复；PAUSED 可按原取消语义结束且不可再恢复。
- **控制点**：所有"新动作准入"复用 `AgentRepository.pauseIfRequested`（意图检查+安全收口二合一）：advance 开始、摘要请求前、`afterResponseSaved` 工具批次前（正常与恢复共用）、工具任务限流等待后实际调用前（未获准入保持 PENDING，哨兵 `AGENT_RUN_PAUSED`，不误标 SKIPPED/失败）、写/提案前。模型请求、业务受理与**摘要出站请求**另有**持久化准入边界**：`beginModelCall`、两个受理服务以及 `beginSummaryAttempt`/`beginSummaryRecompressAttempt`（先 `AgentLeaseScope.verify` 再运行行锁检查暂停/状态，过期 claim 一并拒绝）在事务内检查意图，与 requestPause 的行锁串行化——意图先落库则不发新请求、不受理新业务、不创建摘要请求身份；摘要首次请求的暂停拒绝按控制结果处理，重压缩拒绝结算首次用量并沿用上一份有效摘要（详见 pause-resume 报告第 10 节 F5）。
- **输入续跑**：`AgentRunService.submit` 在权威状态为 PAUSED/暂停等待时先分流（`ResumeIntentRecognizer` 三值：RESUME/AMBIGUOUS/UNRELATED），明确续跑走幂等 resume 且不写 USER 消息/不改 goalRevision；歧义与普通新内容明确拒绝并保留输入；暂停等待期拒绝一切抢先操作。显式绑定 `pausedRunId` 的输入始终留在控制入口、绝不落入普通 createRun：原运行已终态时明确续跑幂等返回真实状态，其余一切未获处理的绑定输入（等待澄清/审批、重试等待、排队/运行中非续跑文本）按权威状态业务拒绝并保留输入；绑定与权威运行不符按 NOT_FOUND 拒绝；未绑定的真正新任务仍按普通提交。前端无继续按钮，`pausedRunId` 仅作作用域绑定；发送与后续消息加载的异步副作用按发送时捕获的项目/会话/恢复代次/绑定 runId 核对（`loadMessages` 返回时同样核对恢复代次），过期副作用不覆盖视图、不清草稿、不换订阅。
- **暂停等待/PAUSED 不计入 active_elapsed_ms**（锚点已清空）；恢复后的下一请求按当前 AGENT 配置解析，已保存结果与待处理调用先于新请求消费（与 R2 恢复共用 5b/afterResponseSaved 规则）。Worker 预算前置检查只回答"是否准入下一次请求"，不拦截"消费已保存结果"。
- 回归：`AgentPauseResumePostgresTest`（12，真实 PostgreSQL）、`ResumeIntentRecognizerTest`（5）；全量 1134 项零失败（证据见报告第 6 节）。

## 9. 正文实时流式展示（2026-10-06 交付）

完整报告：`docs/agent-real-use-and-streaming-report-20261006.md`。沿用完整模型轮次链路，**不新增执行循环**；完整 `ModelTurnResult` 仍走既有校验、结算与分派。改动入口：

- **观察通道**：`infrastructure/ai/turn/ModelContentPreview`（ThreadLocal 观察者，`activate`/`clear`/`push`/`finish`）。适配器在 SSE 聚合循环内把**累计正文快照**推给观察者；未激活时无操作，观察者异常被吞掉（展示失败 ≠ provider 失败）。协调器在 `callModel` 前后激活/清理（`AgentContentPreviewPublisher`），作用域只在本次主模型请求，摘要/恢复/工具路径不激活。
- **临时帧发布**：`AgentEventStreamService.publishContentDelta` 经既有 SSE 连接发送事件名 `MODEL_CONTENT` 的帧 `{modelCallId, revision, text, final}`——**不落 `agent_event`、不占序号、不进 replay、不更新 lastSequence/Last-Event-ID**；发送失败只关闭该订阅。`AgentEventService.publishContentDelta` 是转发入口；`append` 仍是持久事件唯一入口。帧是自包含累计文本，按 revision 幂等替换（丢弃中间帧无缺口）；发布器按 80 字符/300ms 节流，final 帧总是发送。
- **关联字段**：`MODEL_STARTED`/`MODEL_COMPLETED` payload 新增兼容字段 `modelCallId`（ recorder `recordModelTurn(…, modelCallId)` 透传）；历史事件缺该字段时前端按旧规则展示，不影响恢复。
- **前端**：`agent-event-stream.ts` 按 SSE 事件名分流正文帧；`use-agent-workspace.ts` 的 `contentPreview` 状态机（MODEL_STARTED 开启 → 帧幂等替换（8000 字符展示上限，超限仅停止追加）→ 请求结束事件收口 → 最终 ASSISTANT 消息落库后清预览）；`AgentView.vue` 的 `.streaming-preview` 用安全文本插值，最终回答仍用 `marked + DOMPurify` 渲染一次。
- **支持范围**：仅 Zen 传输的 OpenAI 兼容流式路径（`OpenAiCompatibleModelAdapter.turnWithSession` → `turnStreamingSyncWithSession`，生产 OPENCODE_ZEN_FREE 实际路径）。非流式 provider（如 Custom 直连 `adapter.turn`）、Anthropic/Gemini、Legacy CHAT-only 无增量——保持一次性完成展示，不冒充实时生成。
- **部署边界**：帧只在单实例进程内 fanout（与既有事件订阅一致），多实例部署需先解决订阅路由；断线重连不补发预览帧（已提交内容经持久事件恢复，在途请求从后续帧继续或回到分析提示，不因此重发模型请求）。

## 10. 已知边界（后续任务，勿在本文件继续堆功能）

到限续接、精确计费——各自独立设计后再动 `AgentRuntimeCoordinator`/`AgentWorker`（正文实时流式展示已于 2026-10-06 交付，见第 9 节；逐 token 持久化与未提交正文断点恢复仍是独立未列范围）。R1 未确认尾段（最后一次进度 → 进程退出）按设计不计入执行时长，不声称精确计时；真实模型回答与摘要语义质量已于 2026-10-06 完成首批真实验收（报告：`docs/agent-baseline-quality-report-20261006.md`），**真实文档检索成功链已于同日补齐**（报告：`docs/agent-real-use-and-streaming-report-20261006.md`）。主动暂停/继续已交付（见第 8 节），其"子 Agent 全树控制、强制立即中断、暂停时修改目标的智能重规划"仍未列范围。**自动重试状态展示已于 2026-10-06 交付**（纯前端：重试事实全部复用既有事件流与运行字段，重试策略与恢复编排未动，见 `agent-activity.ts` 的 `retryActivities`）。

## 10. 工作状态约束与更正优先级（2026-10-06 修正）

`AgentWorkingState`（`agent_session.working_state`）维护会话权威状态，改动注意：

- **约束只来自 USER 消息**（`appendUser` 三个调用点：createRun、createRetryRun、continueRun），助手响应无写入路径；触发模式是保守词表 + 子句级疑问/否定/假设过滤。2026-10-06 修正：DATE_LOCK 触发词移除单字"别"（"日期…分别…"经 ≤6 字桥接误命中），疑问词表补"能否"。
- **quote 是来源消息中的原文证据**：`repairLegacyV2Entries` 渐进修复只规范化 value/detail，不得覆盖已有 quote（否则溯源信息逐轮被 value 再生文本改写）。
- **用户更正优先级**：`working_state.goalCorrections` 登记显式更正（子句以"更正/修正/再更正/再修正"开头或含"更正：/修正:"，最多 5 条，含 sourceMessageId）。三条不变量：① **quote 保留完整更正原文**（单条上限 1000 字；子句切分会把实际更正内容截掉）；② 读取端（Composer 工作状态块、摘要器 `CURRENT_STATE_FOR_SUMMARY.activeGoalCorrections`）**按时间顺序提供当前目标的全部有效更正**（只取最后一条会让早期更正在原始消息退出窗口后丢失）；③ **显式"新目标："批量退位旧更正**（status=superseded、supersededReason=GOAL_REPLACED，历史保留），读取端只渲染 active 条目——换目标后旧更正不再约束新目标。**不解析更正语义、不改写 activeGoal**。回归：`AgentWorkingStateConstraintIntegrationTest`、`AgentModelMessageComposerV2Test`、`AgentContextSummarizerTest`。

## 11. 预算语义分离（2026-10-07 交付）

完整报告：`docs/agent-budget-semantics-acceptance-20261007.md`（V63、切分公式、无工具总结路径、真实模型验收）。维护要点：

- **`agent_run.budget_semantics`（V63）是计数语义的唯一判据**：`COMBINED`（旧，既有行默认）工具结果逐项占推进步；`SEPARATED`（新根运行/终态重试派生默认）只有模型轮与最终回答落库占推进步，工具结果只计 `tool_calls_used`（`TOOL_CALL_COMPLETED` 步骤/事件/invocation 全保留）。分支表达式内联在 `AgentRunEventRecorder` 各 `steps_used` 赋值处；子运行经 SQL 继承父语义，旧运行恢复/暂停续跑/崩溃接管按原语义累计，不重算历史。
- **委派切分**（`documentResearchDelegationResult`）：委派自身 1 次工具调用；子工具 = min(8, 父剩余−1)；子步数 = min(8, 父剩余−父综合收尾预留 2−委派步成本)；子输入/输出 = 父剩余一半（封顶 30000/8000）；剩余连"子最小研究+子收尾+父综合"都容不下时明确拒绝受理。调整切分必须同时核对回收（`resumeParent` 只计一次）与父收尾预留。
- **终止统一**：批次超总额度 + 有可信证据（父自有 `TOOL_SUCCESS` 或已回收子产出）+ 收尾轮可负担 → `consumeToolBatchQuotaAndRequeue`（按请求封顶消耗额度、整批 SKIPPED、`TOOL_BUDGET_BATCH_REJECTED` ERROR 步骤、重排队）→ 下次准入 FINALIZE 无工具总结。单轮数量超限是协议违规，不走该路径。`decide` 的 `summarizableChildEvidence` 参数只作为 FINALIZE 依据，不计入父成功工具次数。
- **改这里时同步看**：`AgentConvergencePolicy`（公式对两种语义通用）、`AgentWorker` 预算前置检查（数值型）、`AgentRepositoryIntegrationTest`/`AgentDelegationPostgresTest`（两种语义各有镜像断言）。

## 12. 委派结果资料覆盖传递（2026-10-07 交付）

完整报告：`docs/agent-delegated-coverage-delivery-20261007.md`。维护要点：

- **`DELEGATION_COMPLETED.coverage` 是父运行的覆盖事实来源**：`AgentRunEventRecorder.resumeParent` 写回收时，
  用 `agent/domain/model/DelegatedResearchCoverage` 从子运行持久化的 `TOOL_CALL_COMPLETED` 提取
  文档/版本身份、提纲取得状态与可信度、已读章节、分页/截断限制、覆盖缺口、结束原因——
  **不由模型填写、不追加统计用工具调用、不回传工具原文**。改文档研究工具的输出结构时同步看提取器。
- **注入分离**：`AgentRuntimeCoordinator.childResearchEvidence` 同时写 `<CHILD_RESEARCH>`（文字结论，
  UNTRUSTED）与 `<CHILD_RESEARCH_COVERAGE>`（`DelegatedResearchCoverage.renderForParentPrompt`，
  已校验事实），二者不混同。不要把覆盖块合并进 UNTRUSTED 正文块。
- **语义边界**：检索命中（`RELEVANT_EXCERPTS_ONLY`）不计入正文覆盖；`HEURISTIC_HEADINGS` 提纲不是完整目录；
  未取得提纲时未读范围未知，不得枚举未读章节；旧记录缺 `coverage` 字段时按未知渲染
  （`UNKNOWN_FACTS`），**不得**解释成"未取得提纲"。
- **回归**：`DelegatedResearchCoverageTest`（纯函数 9 项）、`AgentDelegationPostgresTest`（覆盖附带、
  分离注入、旧记录、重复回收、提纲级降级）。

## 13. 委派受理拒绝的边界与兜底覆盖事实（2026-10-07 交付）

完整报告：`docs/agent-delegation-rejection-admission-delivery-20261007.md`。维护要点：

- **区分"受理拒绝"与"执行失败"**：`common/exception/AgentDelegationNotAdmittedException` 只表示
  "委派在**当前运行预算下不可能被受理**"（次数耗尽 / 剩余预算容不下子运行最小研究与父综合收尾），
  原因以稳定错误码 `AGENT_DELEGATION_CHILDREN_EXHAUSTED` / `AGENT_DELEGATION_BUDGET_INSUFFICIENT`
  表达。**不要用中文异常消息文本判断原因，也不要把 `AGENT_TOOL_NOT_ALLOWED` 一律软化**：
  depth 边界（子运行不能再委派）、定时运行策略拒绝等仍是普通 `BusinessException`，走原失败边界。
- **受理判定是纯函数**：`agent/domain/model/AgentDelegationAdmission`（`reject(Facts)` / `split(Facts)`）
  被 `AgentRunEventRecorder.documentResearchDelegationResult`（持久化受理事务）与
  `AgentRuntimeCoordinator.withoutUnadminttableDelegation`（工具可见性）共用——**同一判定只有一处**，
  改阈值或切分公式必须同时看这两处。判定只读单调变化的运行事实，同一事实结论稳定（恢复重放不产生新语义）。
- **拒绝的行为**（`AgentToolCallExecutor.rejectDelegation`）：不创建子运行、不写成功 DELEGATED 回执；
  结果（`status=REJECTED`、`delegationAdmitted=false`、`error=<原因码>`）落在委派工具**自己的
  invocation 身份**上并被本模型轮次消费，恢复经 `knownInvocationResult` 复用——**不重复执行、不重复计数、
  不创建第二个子运行**。之后按既有收敛策略走：有可信证据→无工具综合；无证据但可继续→照常发下一次请求；
  都不可负担→证据兜底；无证据→既有 EXHAUSTED。**暂停/取消/权限/租约仍走原边界，暂停不被自动解除**。
  **同 invocation 已受理的动作仍按幂等规则返回既有子运行**，不因后来预算变化变成新拒绝。
- **兜底必须带覆盖事实**：`AgentRuntimeCoordinator.completeFromEvidence` 的**两条**分支都并入
  `collectedChildCoverage(steps)`（复用既有 `DelegatedResearchCoverage.renderForParentPrompt`，
  与 UNTRUSTED 子文字分开）。正常综合与预算兜底**覆盖语义必须一致**：已取得提纲不得被误称未取得、
  `HEURISTIC_HEADINGS` 不是完整目录、检索命中不等于正文读完、旧记录缺 `coverage` 按
  `UNKNOWN_FACTS` 未知兼容、子文字与覆盖事实冲突时**以覆盖事实为准**。兜底文本是**面向用户的交付文本**，
  只陈述事实与优先级，**不要**往里面写提示词指令。
- **委派是显式触发而非默认行为**：根运行非定时才可见该工具，子运行 `maxChildren=0` 最多一层；
  对照实验证明普通提问（`children_used=0`）走直接检索，不创建子运行。不要把"提示词里显式要求委派"
  的实验样本误读为默认路由行为。
- **回归**：`AgentDelegationAdmissionTest`（纯函数 6 项）、`AgentDelegationPostgresTest`
  （拒绝不 FAILED、原因码区分、恢复幂等、无证据继续、可见性收窄、权限/暂停边界、兜底四种覆盖形态）。

## 14. 上下文容量策略 v2（2026-10-08 交付）

完整设计与依据：`docs/agent-context-capacity-design-20261008.md`；本轮交付报告与实测：
`docs/agent-context-capacity-delivery-20261008.md`。迁移 `V64__agent_context_policy.sql`。

### 14.1 唯一策略判据

- **`agent_run.context_policy_version`（V64）是资源策略的唯一判据**，与 `budget_semantics`（V63，
  推进计数语义）是两个不同维度。`AgentResourcePolicy`（domain）是解析入口，**不要在
  Worker/协调器/收敛/委派/仓储里各自复制一份版本判断**。
  - `v1`（既有行默认）：累计 `max_input_tokens`/`max_output_tokens` 参与准入、收敛、
    委派切分与停机；旧运行恢复与暂停续跑**保持原额度含义，不重解释**。
  - `v2`（新根运行与终态重试派生）：累计输入/输出**只统计**，不参与准入、收敛、摘要准入、
    兜底请求或委派拒绝；单次请求守当前模型真实窗口与本次最大输出。
- **无累计上限用 `NULL` 表达**：不用 `0`、8M、`Integer.MAX_VALUE` 或另一组更大的数字冒充。
  所有消费方必须显式处理可空上限，**不得把 JDBC NULL 读成 0**（`AgentRunMappers.nullableInt`），
  也不得对 `NULL` 做旧的 `min`/比较/对半分配。DB 侧计数推进统一走 `agent_capped_add(used, cap, delta)`：
  `cap IS NULL` 时不封顶、只累计；`cap` 非空时保持 `used <= max` 的行约束
  （`ck_agent_run_budgets` 已重建为区分"旧限额行"与"新无限额行"）。
- **模型配置的单次最大输出是另一层限制**，不随 v2 取消，也不与累计额度混用。

### 14.2 三个量必须分开

| 量 | 含义 | 入口 |
| --- | --- | --- |
| `H` 模型安全可用输入 | `W - R - S`，真正的单次硬边界 | `AgentContextBudget.perRequest` |
| `T` 软压缩触发线 | `min(256000, floor(H*0.85))`，小窗口自动提前 | 同上；`Budget.shouldCompact(C)` |
| `L` 压缩后软目标 | `floor(T*0.50)`，软目标不是失败判据 | 同上 |

- **256k 是整理旧轨迹的策略线，不是请求硬上限，也不是整轮累计额度**。超过 `T` 触发压缩，
  不构成拒绝受理、截断必要证据或反复压缩的理由；只要请求仍在 `H` 内就能继续。
- 已确认窗口的 v2 运行**不再叠加应用单次 50k 上限**（`enforcePerRequestCap=false`）：
  单次硬边界就是 `H`。窗口未知时保留 50k 兼容回退并显式标记 `estimated`。
- 输出预留必须等于**本次实际出站值**（`effectiveRequestMaxOutput`），不能用与实际发送不同的数字算窗口。
- 单次输出的派生封顶经 `AiRequestOutputCap` 在**本次调用范围内**下发（适配器读
  `AiRequestOutputCap.effective(config)`），**不改用户持久模型配置**，也不跨请求泄漏。

### 14.3 父子独立执行额度

- v2 子运行使用**自己的**独立上限（根 24 轮/64 步/64 工具、子 12 轮/16 步/24 工具），
  **不从父剩余切分**；累计 token 不切分（v2 本就没有累计上限）。
- **子消耗回收只做真实统计，不扣减父自身的步骤/工具执行额度**（`resumeParent` 的
  `independentChildBudget` 分支）。委派本身仍计父 1 次工具，父仍须能发起委派并完成自身综合收尾
  （`AgentDelegationAdmission.reject` 只检查这一点）。
- **工具可见性必须计入本轮已知推进成本**（F6）：可见性判定在本轮请求发出前，受理判定在模型轮
  落库后，两者之间必然多消耗一个推进步。用 `admitsDelegationForUpcomingTurn(facts)`；
  `combined` 参数取真实持久化语义（`AgentRunView.combinedBudgetSemantics()`），不要固定假定其一。

### 14.4 时长、超时与租约

- v2 活跃执行时长：**根 45 分钟、子 30 分钟**；子**不继承父剩余 deadline**，各自解析各自上限。
- 模型单次请求 **10 分钟**（`JsonHttpModelClient.DEFAULT_REQUEST_TIMEOUT`）、连接超时 **15 秒**、
  内置工具 **30 秒**、MCP **120 秒**、工具结果字节保护 **128kB** 级。
- **不要重新引入固定的 300000ms 隐藏截停**：`AgentRunEventRecorder.recordFailure` 的
  "活跃时长到限"判断必须读 `AgentRuntimeLimits.forRun(...).maxRunDuration()` 这一有效策略。
- **长请求必须配套有界租约续期**（`AgentLeaseRenewer`）：单次请求可达分钟级而 claim 租约仍是
  短窗口（默认 6 分钟）。请求进行期间按短周期把租约推到"当前时刻 + 短窗口"，
  **只有仍持有当前 claim 的活跃 worker 可续租**（复用 `AgentLeaseScope` 的 claim epoch 做
  fencing）；失去租约/取消/到限/退出时立即停止续租。**不要把租约直接延长到几十分钟**——
  那会让崩溃接管明显退化。续租是独立短事务，**不跨 HTTP 持数据库事务锁**，不建第二套调度器。

### 14.5 运行研究轨迹窗口压缩（RUN_CONTEXT scope）

- 复用 `AgentContextSummarizer` 的生成/校验/记账规则与同一持久化准入边界，**不建两套执行循环**。
  两个 scope 的区别只在落点：主会话摘要写 `agent_session.working_state.summary`；
  **RUN_CONTEXT 写本运行 `agent_step` 的 `output_json`**（`reason='RUN_CONTEXT_SUMMARY'`），
  因此**子运行只生成自己的 RUN_CONTEXT，不触发也不改写主会话摘要**。
- **每个运行最多 4 个有效压缩周期**（成功提交才占周期；失败/不合格不推进覆盖也不占额度）；
  **每个 `scope + 源边界 + 目标修订` 至多一个有效周期**，已覆盖前缀不重复压缩。
- **成功提交才推进覆盖**：失败/不合格/暂停/取消一律保留旧有效摘要与原数据，返回未提交。
  来源范围由**实际送入摘要请求的记录**计算，模型不能填写"这些步骤全读过"；
  截断时只覆盖完全落入截断点的前缀。
- **提交成功后调用方必须重新组装本次主请求**（协调器已如此），不能继续发送压缩前组好的 messages。
- 未消费的模型响应、PENDING 工具调用与最新一轮**永远不进入压缩范围**（属必要层）。

### 14.6 原文优先与正文页

- **移除** `6000`/`1500`/`24000` 固定投影顶：`NEWEST_TOOL_OUTPUT_CAP=48000`、
  `OLDER_TOOL_OUTPUT_CAP=12000`、工具观察层 6000–120000 字符；Projector 仍是有压力时的
  确定性投影组件，**不是正常情况下强制丢正文的入口**。
- `HISTORY_CANDIDATES=40` 只是**查询候选**上限，不是"窗口有空间却只能看 40 条"的逻辑丢弃边界。
- 正文页默认 **12000**、最多 **24000 字符**：常量在 `DocumentContentService`
  （`DEFAULT_MAX_CHARS`/`MAX_MAX_CHARS`/`MIN_MAX_CHARS`），工具 schema、执行边界与描述
  **必须同源**（`DocumentAgentToolContractTest` 锁定）。放大正文页时**同步核对结果字节保护**
  （`AgentToolResultSanitizer.maxResultBytes()`），不能只改 `maxChars` 后在另一层被拒。
- 子研究上下文隔离：`AgentContextAssembler` 不为 depth>0 加载父提案；
  `AgentModelMessageComposer` 的 **v2 与 `composer-v2=false` 两条路径**都不给子运行
  父工作状态/会话摘要/主会话历史/项目记忆。**不要靠追加"忽略上文"提示词代替数据选择**。

### 14.7 委派覆盖事实（F3/F4 修复）

`DelegatedResearchCoverage` 的输出被标注为"已校验事实"且提示词要求冲突时以它为准，
因此投影必须严格忠于工具结果：

- **章节 ≠ 片段**：`sectionsRead.count` 是**去重标题**数，`fragmentCount` 才是片段数。
- **续读可以闭合**：按 `continuation` 续到结尾（`hasMore=false`）后不再声明"后续内容未读完"；
  只有仍有**未消费的续读点**时才报未读完。
- **半截读取不是读全**：从 chunk/offset 中间读到的后缀只进 `partiallyReadHeadings`，
  并报告"起始前缀未被覆盖"缺口。
- **重复提纲不翻倍**：`sectionsListed` 是去重后的提纲事实，不是调用返回条数累加。
- **不跨快照合并**：状态按 `(documentId, snapshotId)` 建键，不同快照各自成条并把版本冲突
  写入 `gaps`；快照未知时单独成键，不推断与已知快照同版本。
- 旧记录缺 `coverage` 仍按 `UNKNOWN_FACTS` 未知兼容。

### 14.8 研究链的其余修复

- **F1**：子运行（depth>0）的 `[QUESTIONS]` **不得**进入 `WAITING_FOR_USER_INPUT`——
  那会让父运行等待一个用户看不到也无法回答的隐藏问题。改为降级成"研究缺口"并正常收口。
- **F2**：**所有组装路径共用一个入口** `AgentRuntimeCoordinator.assembleRequestMessages`
  （基础消息 + 子证据 + 本轮指令）。新增组装分支必须走它，否则会重演"降级重组/强制收尾
  丢掉子成果"。
- **F8**：输出超额分支**保存已返回正文为部分产出**（`recordBudgetExceeded(run, completion)`），
  `resumeParent` 移交正文而不是只给 `AGENT_BUDGET_EXCEEDED` 占位符；终态仍如实为
  `BUDGET_EXCEEDED`，不执行该响应提出的新工具。

### 14.9 改这里时同步看

`AgentContextBudgetTest`、`AgentResourcePolicyTest`、`AgentDelegationAdmissionTest`、
`DelegatedResearchCoverageTest`、`AgentRunMappers`+`AgentRunView`（可空上限与策略字段的
单一映射）、`AgentRepository.createRun/createRetryRun`（v2 落库）、
`AgentRunEventRecorder`（`agent_capped_add` 与回收分支）、前端 `types.ts`+`AgentView.vue`
（无累计上限时不展示"剩余额度/百分比"）。

**前端契约**：`maxInputTokens`/`maxOutputTokens` 在类型上是 `number | null`；
`null` 表示累计只统计，**不得当作 0**、不展示剩余额度或百分比；父步骤/工具标明为
**本运行自身**计数，不与全树消耗混用。这不是对话工作区重设计，也未重开 SSE。

