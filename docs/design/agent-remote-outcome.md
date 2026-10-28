# Agent 远端结果确认与安全恢复（P6-B）

任务：`P6_AGENT_REMOTE_OUTCOME_01`。基线：`968db8ce0f50d6a028793002386cb23cfb9a2024`。

## 审计与不变量

实际路径：MODEL_TURN 事务持久化 → invocation → Scheduler → 工具 → 顺序记录结果/额度/事件 → Run 收口或重新排队。
原 invocation ID 由 run ID、持久化 MODEL_TURN sequence、ordinal 确定；tool name 和 JSONB arguments 在恢复时另行严格核对。
Run 绑定 project/requester；不按参数跨项目去重，也不把 ToolCall ID 当成业务幂等键。

旧超时路径统一 FAILED/retryable=true，不能证明远端未成功；PENDING 不区分尚未出站与结果丢失。
结果记录缺少 PENDING 条件，允许已结算 UNKNOWN 被后来的结果覆盖。
markBatchHandled 只保留既有 UNKNOWN，但会把仍为 PENDING 的已出站调用改为 SKIPPED。
Scheduler 的线程不继承 ThreadLocal claim；必须在调用闭包内显式恢复 claim，调度器本身保持不变。

必须保证：先提交出站标记才调用工具；失权 Worker 不得提交结果；任何取消/中断不证明远端撤销；
UNKNOWN 不可被普通结果写入覆盖；同 Run 有不确定操作时停止自动业务推进；只读工具保留有界重试。

并发结算的最终分类必须在 `recordToolExecutionFailure` 事务中完成：先锁 Run，再锁当前轮次 Invocation，
取得最新已提交的 dispatch_started_at / execution_safety 后判断 UNKNOWN；事务外的 dispatchedUnsafe 只能用于恢复/规划查询提示。
不能把先前的“未出站”读值直接当作 FAILED/retryable 的落库依据。出站事务先提交则不确定异常落 UNKNOWN；
结算先提交则原 Invocation 已非 PENDING，后续 dispatch CAS 失败。出站事务回滚或可信 SAFE_READ 仍保留正常失败/重试语义。
携带 toolCallId 的结果必须命中当前模型轮次的唯一 Invocation，身份匹配且为 PENDING，并检查最终 UPDATE 恰好影响一行；
缺失、旧轮次、终态或参数冲突均在记账前拒绝，事务回滚不留下孤立 Step/Event 或额度消耗。

## 可信能力分类

| 类别 | 声明与策略 |
| --- | --- |
| SAFE_READ | 内置查询工具按实际实现显式声明；失败按现有 TOOL_RETRY 预算处理 |
| IDEMPOTENT_OR_RECONCILABLE | 受控 Planning 工具；使用原 invocation 查询真实 operation，仅查询既有结果，不自动重发 |
| UNCONFIRMABLE_SIDE_EFFECT | 默认类别，包括仅有远端 readOnlyHint 的 MCP；已出站后不能确认则 UNKNOWN |

审批提案与文档研究委派使用既有事务受理与幂等路径，不进入普通工具出站执行。
MCP 白名单、Schema hash、只读提示复核、权限与结果净化继续执行；本设计不开放 MCP 写工具。

## 持久化状态机

最小增量字段位于既有 invocation 表，无新增执行表。dispatch_started_at 是提交在工具执行之前的保守出站证据，
execution_safety 固定本次宿主能力，dispatch_claim_version 关联原租约。
原 PENDING 自身不能证明崩溃时工具是否已经出站，因此需要单独字段；不修改历史迁移。
旧 STARTED 是正向出站线索，但没有冻结宿主安全类别和原 claim，也没有 invocation 级原子单次准入条件；
它不能独立承担执行账本。新字段在原 invocation 行上原子 CAS，避免把可重复的通知事件当成可再次发送的授权。
V67 同时根据 V66 已有 TOOL_CALL_STARTED（按 invocationId 精确关联）回填旧 PENDING 的出站证据，
历史没有记录原 claim 的行使用 -1，不伪造 epoch。旧路径保证开始事件在工具执行前提交；没有开始事件的旧 PENDING 保留未开始语义。
已有开始事件的旧 CANCELED/SKIPPED 及 TOOL_EXECUTION_TIMEOUT 失败同样转 UNKNOWN，保留旧 Step 历史；
没有可信历史安全类别时保守处理，不按工具名称猜测安全性。终态 Run 不被迁移自动复活。

| 当前事实 | 下一步 |
| --- | --- |
| PENDING，无 dispatch | 尚未执行；暂停保持 PENDING；取消/批次结束可 SKIPPED |
| PENDING，有 dispatch，SAFE_READ | 可在授权、租约、预算范围内恢复查询 |
| PENDING，有 dispatch，其他类别 | 原身份只读核对；有真实 operation 则复用，否则 UNKNOWN |
| 可信工具返回成功/明确拒绝 | PENDING → SUCCEEDED/FAILED/REJECTED |
| 已出站、超时/响应丢失/无法确认 | PENDING → UNKNOWN，retryable=false |
| UNKNOWN | 普通写入不允许覆盖；Run 非重试 FAILED，需人工核对 |

出站标记提交后、网络发送前崩溃也保守 UNKNOWN，允许可用性损失，禁止盲目重发。
超时后迟到结果不自动升级 UNKNOWN。中断被忽略时由 P6-A 保持额度直到 Callable 实际退出。
受控规划超时时先查询已提交 Operation；若查询时受理事务尚未提交，保守 UNKNOWN，后续需人工核对，不自动复活已终结的 Run。
远端 MCP 的 isError 不足以证明没有部分副作用，保守 UNKNOWN；明确的可信宿主拒绝结果可保持 REJECTED。
旧 claim 的出站与规划写入均受 fencing；出站标记与真正网络请求之间无法跨系统原子取消，故接管仍须保守核对。

## 业务身份与恢复

Planning operation.invocation_id 唯一，业务 Operation ID 与 invocation 分离；查询返回原 operationId/planId/attemptId。
核对必须继续验证项目权限、请求用户、原工具名与参数，不创建新的规划操作。
UNKNOWN 阻断同 Run 后续不安全调用，包括新 ToolCall ID；不声称跨新 Run、跨服务 Exactly-Once。
不同参数不推断属于同一业务操作；缺少远端幂等身份或查询能力时不提供通用“查询远端状态”假接口。

## 验收边界

使用独立 Testcontainers PostgreSQL、确定性副作用计数器与闭锁；矩阵与实际证据在本任务交付报告。
不连接生产数据库，不调用付费模型，不执行真实外部 MCP 写操作。
真实 Provider/MCP 网络取消确认：`LIVE_NOT_RUN`。
P6-A 配额、公平调度与队列保持原实现；不提供分布式配额或跨系统 Exactly-Once 保证。
