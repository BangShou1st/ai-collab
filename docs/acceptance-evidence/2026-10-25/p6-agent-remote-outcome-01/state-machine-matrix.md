# P6-B PostgreSQL 故障矩阵

实际测试类：`AgentRemoteOutcomePostgresTest`；升级场景另见 `AgentRemoteOutcomeMigrationPostgresTest`。
所有工具为本地确定性 Fake；远端副作用用独立 AtomicInteger 或独立提交的 PostgreSQL 业务操作行计数。
操作计数不是 Mockito 调用次数。响应阻塞/忽略中断使用 CountDownLatch，不用 sleep 猜测竞争。
测试使用真实事务代理、实际 Run/Invocation/Step/Event 表；规划受理使用真实 operation 表唯一约束。
逐项执行结果以 `backend-verify.json` 为准，本表记录断言口径。

| ID | 故障注入与对应方法 | 操作次数 | Invocation / Run 与关键断言 |
| --- | --- | --- | --- |
| T01 | `t01NeverStartedTimeoutHasNoEffect`：零截止预算 | 0 | FAILED / QUEUED；dispatch NULL；无出站 |
| T02 | `t02SuccessfulRemoteEffectWithLostResponseIsUnknown`：独立计数后丢响应 | 1 | UNKNOWN / FAILED；不可重试；UNKNOWN 失败事件恰好 1，无成功 Step |
| T03 | `t03ExplicitRemoteRejectionIsNotUnknown`：明确拒绝回执 | 0 | REJECTED / QUEUED |
| T04 | `t04CrashAfterDispatchNeverRedispatchesSameInvocation`：提交出站标记并远端受理，丢本地结果 | 1 | UNKNOWN / FAILED；再次 dispatch 拒绝 |
| T05 | `newToolCallIdMustNotRepeatAnUnconfirmedEffect`：原操作不确定，新模型 ID | 1（新增 0） | 原 UNKNOWN 保留；新调用不执行；Run FAILED |
| T06 | `t06TimeoutThenLateRemoteSuccessDoesNotOverwriteUnknown` + `t06CancelAfterDispatchPreservesUncertainEffect` | 各 1 | UNKNOWN / FAILED 或 CANCELED；超时后 Callable 仍占 1 额度，晚成功不覆盖 |
| T07 | `t07OldClaimIsFencedAndTakeoverDoesNotResend`：真实 claimNext 接管过期 Lease | 1 | 旧 claim 写入拒绝；新 Worker UNKNOWN / FAILED，零重发 |
| T08 | `t08PauseBeforeDispatchKeepsPendingAndResumeExecutesOnce` | 暂停 0，继续后 1 | 暂停 PENDING / PAUSED；恢复 SUCCEEDED，原 invocation 不变 |
| T09 | `t09AcceptedPlanningOperationIsReconciledByOriginalInvocation` | 1 次受理、1 个 operation | SUCCEEDED / SUCCEEDED；原 operationId；重复 service 返回原结果；真实唯一约束拒绝重复 INSERT |
| T10 | `t10McpWithoutTrustedIdempotencyDoesNotResend`：无法重新授权的外部工具出站证据 | 1（新增 0） | UNKNOWN / FAILED；工具名称/readOnlyHint 不能授权重发 |
| T11 | `t11SafeReadFailureRetainsBoundedRetry` | 2 次查询，0 业务副作用 | 初次 + 1 次重试；第三次 REJECTED；TOOL_RETRY=1 |
| T12 | `t12PartialBatchKeepsSuccessAndUnknownDistinct` | 2（新增 0） | SUCCEEDED 与 UNKNOWN 同时保留；settled=2；Run FAILED |
| T13 | `t13ResultTransactionConflictCannotLeaveFalseSuccess` | 1 | 冲突事务无成功 Step/Event；PENDING 标记保留；恢复 UNKNOWN / FAILED |
| T14 | `t14UnknownBlocksFinalSuccessAndModelContinuation` + `requeueCannotTurnUnconfirmedDispatchIntoSkippedOrRetryableFailure` | 新增 0 | 禁止 final success；模型调用 0；禁止重排队/模型重试预算消耗；保留 UNKNOWN |

补充场景：

- `cancelBeforeDispatchNeverStartsTool`：取消先提交，副作用 0、dispatch NULL、Invocation CANCELED。
- `conflictingResultIdentityCannotCreateSuccessOrSpendBudget`：错误参数不能伪造成功，不新增工具额度和成功事件。
- `acceptedResultCanBeQueriedWhileOriginalResponseIsStillBlocked`：实际独立事务业务行受理后阻塞回执，查询原操作成功；Run SUCCEEDED、1 个 operation、1 次工具记账，原 Callable 仍占额度直到退出。
- `unknownCannotBeOverwrittenByLateSuccess`：普通结果 API 不能覆盖 UNKNOWN。
- `batchSettlementMustPreserveUnknown`：批次终结不把 UNKNOWN 变 SKIPPED。
- `v66StartedCallsRemainProtectedWhenDispatchColumnsAreIntroduced`：真实 V66 → V67；旧 STARTED/PENDING 被保护，无 STARTED 的旧 PENDING 仍可识别；不确定调用不会进入自动重试。

## 修复前

`before-unit.json`：超时结果为 FAILED，预期 UNKNOWN；取消后的底层 Callable 仍可产生一次副作用（原调度器已正确保留额度）。
`before-postgres.json`：2 个真实断言失败——新 ToolCall 产生额外一次副作用，UNKNOWN 可被成功覆盖。
该修复前 T05 版本只统计新增操作，修复后强化为同时统计原远端受理与新增调用。
原暂停 21 项、持久化模型轮次恢复 24 项均通过，其中旧 Lease 和已受理规划幂等是既有正确防护，未人为制造缺陷。

## 验证边界

本地 Fake 证明宿主的持久化、fencing、重试与恢复行为；不代表真实远端服务实现撤销或 Exactly-Once。
实际外部 MCP/Provider 网络取消确认：`LIVE_NOT_RUN`。
