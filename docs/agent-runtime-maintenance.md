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

## 9. 已知边界（后续任务，勿在本文件继续堆功能）

到限续接、自动重试体验、逐字流式、精确计费——各自独立设计后再动 `AgentRuntimeCoordinator`/`AgentWorker`。R1 未确认尾段（最后一次进度 → 进程退出）按设计不计入执行时长，不声称精确计时；真实模型回答与摘要语义质量仍是独立未验收项。主动暂停/继续已交付（见第 8 节），其"子 Agent 全树控制、强制立即中断、暂停时修改目标的智能重规划"仍未列范围。
