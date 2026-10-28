# P4-AGENT-LIFECYCLE-REAL-E2E-02：Agent 生命周期机制审计（阶段 1 交付）

> 审计方式：只读源码审查（两个独立审计代理交叉 + 主会话关键点人工复核），全部结论以源码为准并附 file:line。
> 审计基线：`e1d276557573f3a8f013ce98d4e93af6a5ff9d16`（dev = origin/dev）。
> 本文只记录机制事实；真实浏览器场景执行状态见 [lifecycle-scenarios.json](lifecycle-scenarios.json)。

## 1. 状态机与合法转移

状态 11 个：`CREATED, QUEUED, RUNNING, PAUSED, WAITING_FOR_APPROVAL, WAITING_FOR_USER_INPUT, SUCCEEDED, FAILED_RETRYABLE, FAILED, CANCELED, BUDGET_EXCEEDED`；终态 = SUCCEEDED/FAILED/CANCELED/BUDGET_EXCEEDED（`agent/domain/model/AgentRunStatus.java:3-18`，路径均相对 `ai-collab-backend/src/main/java/com/shitulelv/aicollab/`）。

合法转移（`agent/domain/policy/AgentStateMachine.java:19-42`）：

| 源状态 | 允许目标 |
| --- | --- |
| QUEUED | RUNNING、**PAUSED**、CANCELED、FAILED、BUDGET_EXCEEDED |
| RUNNING | QUEUED、PAUSED、WAITING_FOR_APPROVAL、WAITING_FOR_USER_INPUT、SUCCEEDED、FAILED_RETRYABLE、FAILED、CANCELED、BUDGET_EXCEEDED |
| PAUSED | 仅 QUEUED、CANCELED |

关键结论：

- **QUEUED 可以直接暂停**：`requestPause` 对 QUEUED/FAILED_RETRYABLE 原子转 PAUSED（`agent/infrastructure/repository/AgentRunEventRecorder.java:736-745`）；数据库 CHECK 约束（V61 迁移 `ck_agent_run_status`）与状态机一致。
- **RUNNING 暂停是"意图 + 动作边界确认"两段式，不是立即生效**：`requestPause` 对 RUNNING 只写 `pause_requested_at=now()` 并发 `RUN_PAUSE_REQUESTED` 持久事件（`AgentRunEventRecorder.java:726-758`），且刻意**不递增 version**（注释 :714-716：避免在途响应落库 CAS 失配）。Worker 在每个新动作准入检查点调用 `pauseIfRequested` → `recordPaused` 确认（RUNNING→PAUSED、清租约、结清活跃时长，`AgentRunEventRecorder.java:797-820`）。检查点分布：`AgentWorker.java:67`、`AgentRuntimeCoordinator.java:172/330/446/892`、`AgentToolCallExecutor.java:203/267`、`AgentApprovalService.java:74-77`。
- **PAUSED 恢复 = 同一个 Run**：`requestResume` 仅 PAUSED→QUEUED，清 `pause_requested_at` 与 `finished_at`（`AgentRunEventRecorder.java:767-789`）；`AgentRunService.resume` 注释明确"保留原 runId、目标、约束、额度与已完成记录；不新建派生运行"（`agent/application/AgentRunService.java:277-285`）。另有文本续跑意图分流（`AgentRunService.submit` :158-182，`ResumeIntentRecognizer`）。

## 2. 暂停恢复后的工具与预算

- **已完成的工具不会重新执行**：暂停确认 `recordPaused` 不调用 `markBatchHandled`——未消费的模型轮次与 PENDING 工具调用全部保留（`AgentRunEventRecorder.java:791-796` 注释）。恢复后协调器优先复用 `pendingModelTurn` 已落库的模型响应（`AgentRuntimeCoordinator.java:216-222`，SQL `AgentRepository.java:360-372`）；恢复批次中已有持久化结果的调用按原 invocation 身份（最近模型轮次 + tool_call_id）识别，复用结果、不再执行、不占新增工具额度（`AgentRepository.java:374-388/805-818`、`AgentToolCallExecutor.java:135-138/905-911`）。
- **预算保留不重置**：`requestResume` 的 UPDATE 只改 status/pause_requested_at/finished_at/version，不触碰 `steps_used/tool_calls_used/*_tokens_used`（`AgentRunEventRecorder.java:776-780`，注释 :717）。

## 3. 取消收口

入口 `AgentRunService.cancel`（:294-308）→ requireMember → `repository.requestCancel` → 事务提交后 `cancellationSignal.interrupt(runId)` 中断在途 Worker 线程。

`requestCancel`（`AgentRepository.java:281-326`）：

- `SELECT ... FOR UPDATE`，幂等（已 CANCELED 直接返回）。
- 可取消状态：QUEUED / RUNNING / WAITING_FOR_APPROVAL / WAITING_FOR_USER_INPUT / FAILED_RETRYABLE / PAUSED；终态抛 `AGENT_RUN_NOT_CANCELABLE`。
- **RUNNING：只记 `cancel_requested_at`（COALESCE 防覆盖），状态保持 RUNNING**，由 Worker 在检查点异步收口。
- 非 RUNNING：**同事务直接转 CANCELED**，写 finished_at、清租约。

已消耗模型调用不丢账：响应返回后发现取消先 `settleOrphanUsage` 幂等结算再抛取消（`AgentRuntimeCoordinator.java:531-543`）；落库事务回滚后按同一 modelCallId 补结算（:572-584）；`recordCanceled` 将 PENDING 工具 invocation 置 CANCELED 并 markBatchHandled（`AgentRunEventRecorder.java:688-710`）；接管方 `AgentRecoveryJob.claim` 即 `closeUnresolvedModelCalls`（显式 0/0 + UNKNOWN，不虚构消耗）。

## 4. FAILED Run 重试

- **FAILED_RETRYABLE**（错误码 AI_MODEL_TIMEOUT / AI_PROVIDER_ERROR / FORMAT_REPAIR_REQUESTED 且未达上限）→ 落 `retry_after=now()+30s`，`claimNext` 自动领取**原地重新排队**，retry_count 上限 10；模型重试经 `retryCount>=2` 判最终失败（`AgentRunEventRecorder.java:648-681`、`AgentRepository.java:429`）。
- **终态（FAILED/CANCELED/BUDGET_EXCEEDED）手动重试 → 派生新 Run**：`createRetryRun` 复制 goal/skillCode/pageContext，`retried_from_run_id` 记来源，预算从默认值开始（不继承已耗尽额度与取消标记）（`AgentRunService.retry` :310-351、`AgentRepository.java:178-222`）。
- **幂等**：部分唯一索引 `ux_agent_run_retried_from ON agent_run(retried_from_run_id) WHERE retried_from_run_id IS NOT NULL`（V58 迁移）；INSERT `ON CONFLICT DO NOTHING`，冲突返回既有派生运行（`AgentRepository.java:200-211`）。数据库已实测存在该索引。
- 本轮候选 Run 核查：`24b71f55`（FAILED, AGENT_TOOL_EXECUTION_FAILED）与 `ea469167`（FAILED, PARAMETER_CORRECTION_EXHAUSTED）均无派生 Run、无未完成审批，可安全重试。

## 5. 租约与 claim_version fencing

- `claimNext`：`FOR UPDATE SKIP LOCKED` 领取 QUEUED / 租约过期 RUNNING / retry_after 到期 FAILED_RETRYABLE，置 lease_owner、租约 6 分钟、claim_version=version+1（`AgentRepository.java:419-451`）。
- Fencing：`AgentLeaseScope`（ThreadLocal epoch）在所有写路径 `verify()`——claim_version 不匹配或租约过期即拒绝写入；cancel_requested_at 非空且不允许取消时抛 AGENT_RUN_CANCELED（`AgentLeaseScope.java:21-29`）。模型请求期间由 `AgentLeaseRenewer` 短周期续租（仅 claim_version==epoch 可续，`AgentRepository.java:473-486`）。
- 终态三层禁写：claimNext 只领非终态；终态写入同事务清租约 + markBatchHandled；旧 Worker 后续写被 epoch 校验拒绝。

## 6. SSE 重放

- Last-Event-ID 与 afterSequence 取 max 作为游标（`AgentEventController.java:28-49`）；`AgentEventStreamService.subscribe` 先 requireMember + findRun 鉴权，再按游标从 `agent_run_event` 批量追赶（BATCH 500/上限 2000，:255-269）。
- 防回退：只发送 `sequence > lastSequence` 的事件，写成功才推进游标（:272-288）；Run 详情携带 `lastEventSequence` 供前端重订阅起点。临时 `MODEL_CONTENT` 帧不落库不占序号。终态按 TERMINAL 集合关闭连接。

## 7. 权限与隔离

- 读取/控制接口全部 `access.requireMember(projectId, userId)`（`AgentRunService.java` 17 处公开方法首行）；审批 resolve 另加 `requireAdmin`（`AgentApprovalService.java:321/380`）；仓储 SQL 均带 `project_id=?`。
- 审批权限：仅项目 OWNER/ADMIN（`requireAdmin` → `isAdminOrOwner`）；非成员得到 PROJECT_NOT_FOUND（不泄露存在性）；九项校验（存在/归属/未处理/nonce 常量时间比较/24h 过期/实体存在/version 匹配等）；重复解决按 idempotencyKey+approver+nonceHash 幂等，否则 AGENT_APPROVAL_CONFLICT（`AgentApprovalService.java:297-333/380-410`）。
- 跨项目工具执行不可由模型参数注入：`AgentToolContext` 使用 `run.projectId()/run.requesterId()/ctx.projectRole()`（`AgentToolCallExecutor.java:125-127`），role 由 requireMember 从成员关系解析（`AgentContextAssembler.java:97-100`），约定"模型参数中的 projectId/userId/role 必须忽略或拒绝"（:32-33）。
- 会话列表为**项目级共享**（`AgentRepository.listSessions` :82-88 按 project_id 查询，不按 creator 过滤）——同项目成员互相可见会话，属协作设计；跨项目由 requireMember 拦截（本轮负面测试实证，见 permission-isolation.json）。

## 8. AgentToolPolicy 与 AgentToolRegistry.allowed 双轨判定

- `AgentToolPolicy`（`agent/domain/policy/AgentToolPolicy.java:9-25`）：永久禁止名单 10 项 + `writesBusinessData` 要求 depth==0 且角色 ∈ {OWNER,ADMIN,MEMBER,SUPERVISOR}。**生产执行链不引用它**——全仓仅 `AgentToolRegistryTest` 引用，作为测试对照物。
- `AgentToolRegistry.allowed`（`agent/infrastructure/tool/AgentToolRegistry.java:177-186`）：唯一生产判据。ControlledWrite（规划写）→ depth==0 && !scheduled && OWNER/ADMIN（**比 Policy 严格**，Policy 会放行 MEMBER/SUPERVISOR）；其余业务写 → depth==0 && 角色 ∈ WRITE_PROPOSAL_ROLES。永久禁止名单中的工具名在注册表中不存在（构造时校验重复/未知工具）。
- 结论：**无规则漂移导致的权限放宽风险**（生产单判据 + 测试对照物是刻意设计）；两处安全测试存在（AgentToolRegistryTest / AgentToolRegistryDepthTest）。仅错误消息复用"业务写工具"文案与 ControlledWrite 场景略不匹配（消息层面小瑕疵，非权限漂移）。按任务书 P3 约定只记录，不重构。

## 9. 预算与循环保护

- `AgentRuntimeLimits` 硬编码：默认 16 步/8 模型轮/12 工具调用/单轮 4 工具/3min；V2 策略根 24 轮/64 步/64 工具、活跃 45min，子 12 轮/16 步/24 工具/30min（`AgentResourcePolicy.java:37-70`）。application.yml 无覆盖项（防止配置放松）。
- 纠正/修复经 `agent_recovery_counter`（FORMAT_REPAIR/PARAMETER_CORRECTION/TOOL_RETRY 各 max=1）；`AgentLoopGuard` 滑动窗口 6、重复阈值 3。

## 10. PLANNING_JSON 探针

- 单步入口存在：`POST /api/v1/user/ai-providers/{id}/test-capability?mode=PLANNING_JSON` 各模式互斥分支，**不会**连带执行 CONNECTIVITY/STREAMING（`UserAiProviderController.java:73-79`、`AiCapabilityProbeService.java:140-174`）。
- 最坏真实请求数 4：骨架 1 + 骨架修复 1 + 细节 1 + 细节修复 1（`AiCapabilityProbeService.java:154-167/229-239/266-294`），与生产 `generateSkeletonWithOneRepair` 同口径。
