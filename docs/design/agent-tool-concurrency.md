# P6-AGENT-CONCURRENCY-01 — Agent 工具调度隔离与有界准入设计

> 状态：**IMPLEMENTED / 验收结果见交付报告**
> 设计日期：2026-10-25
> 源码基线：`BangShou1st/ai-collab`，`dev@8ee1cebbf43ab3c1b06a4982d3f3d4ad70ec60ba`
> 范围：Agent Tool 调度器、只读/受控规划工具批次的异步准入、度量、对应回归。**不涉及 PR #18 合并，不改 main，不动审批授权/模型路由/数据库迁移。**

## 0. 实施说明（2026-10-25，优先于下文的设计建议）

本设计的精确身份配额、有界准入、两层轮转、取消安全、指标与业务接入均已实施。
下面保留设计输入的缺陷证据、方案比较、风险与验收矩阵；A1–A6 行号描述的是基线，不是当前源码。

实际 API 为包内 `<T> AgentToolScheduler.Ticket<T> submit(AgentToolContext,long,Callable<T>)`。
Ticket 实现 Future；内部 phase/timeout 提供原子“是否曾开始”的诊断，不暴露业务持久化。
未单独新增 ToolScheduleHandle/AgentToolSchedulerMetrics 文件，避免无必要的组件拆分。
配额为 global=16 / user=4 / (user,run)=2，等待容量=64，执行池固定16线程、交接队列16。
UserState 使用精确 UUID 的 LinkedHashMap，每个用户的 RunState 也使用 LinkedHashMap；
选中用户/Run 后移到末尾，等价于两级 RR。同 Run 内 FIFO，跳过未就绪 Run。
单锁同时预占三层额度；执行线程只执行已 RESERVED 的任务，不获取 Semaphore。

- 状态：PENDING→RESERVED→RUNNING→COMPLETED；未开始终态为 REJECTED、EXPIRED_NOT_STARTED、CANCELLED_NOT_STARTED。
  运行中取消为 CANCEL_REQUESTED_RUNNING，Future 可先完成取消，但 active 只在 Callable 的 finally 退出后归还。
- 每个未开始任务有一个截止定时项（单 daemon 定时线程，remove-on-cancel）；待运行任务到期主动移出并释放，
  RUNNING 时取消该定时项，由现有调用方的绝对截止等待收口 inflight 超时。截止计时从提交前起算。
- 快照 active 包含 RESERVED 与尚未实际退出的 RUNNING；pending 只含 PENDING。稳定锁边界 active≤16、pending≤64。
  队列满时仍可接纳可立即运行的其他身份任务。身份 map 仅保留 active/pending，历史 UUID 不缓存。
- 超时与暂停竞争：先原子终止/阻止未开始任务，再检查用户取消（优先）、暂停（其次）；未开始且暂停已请求时保持 invocation=PENDING。
  运行中超时继续使用既有 TOOL_EXECUTION_TIMEOUT，不改数据库业务状态机；远端副作用 UNKNOWN 留待 P6-B。
- 工具验证、审批、结果按原序持久化、事件身份与 Lease/Fencing 仍由原业务入口处理；提交阶段异常也取消本批已提交句柄。
- 线程池提交拒绝回滚额度，异步拒绝同样映射 TOOL_EXECUTOR_BUSY；关闭拒新、取消等待/预占任务、请求中断运行任务。
  关闭不承诺无视中断的远端调用立即结束，其占用继续如实保留。
- Metrics 使用 Spring 注入的 MeterRegistry，无新依赖或 Actuator 权限改动；完整口径见第7节。
  queue.wait 只含真正启动项，execution 含真实 Callable I/O 时间；未开始过期不会产生 queue.wait 样本。
  timeout 与 cancelled 各计其事件，超时主动请求取消会同时计入两类；自动未开始过期只计 timeout。
  active/pending 是准入账本快照，不是网络服务端并发或整批 Worker 状态。

未实施/非目标：分布式准入、网络撤销确认、UNKNOWN 持久化重构、Provider/RAG/Worker 租约架构、数据库迁移、前端 UX。
验收、原始数量与性能限制见 [交付报告](../archive/reports/p6-agent-concurrency-01-delivery-20261025.md)
和 [证据](../acceptance-evidence/2026-10-25/p6-agent-concurrency-01/README.md)。

## 1. 问题、源码证据与边界

| 编号 | 现状 | 证据 | 定性 |
| --- | --- | --- | --- |
| A1 | `users[256]` 每槽 `Semaphore(4,true)`，`runs[1024]` 每槽 `Semaphore(2,true)`，都按 UUID `hashCode()` 取模 | `AgentToolScheduler.java:10–17` | **正确性缺陷**：不同用户、Run 哈希撞槽共享配额 |
| A2 | `AgentToolCallExecutor` 先 `executor().submit`，任务在 16 工作线程内 `scheduler.acquire`（可能阻塞） | `AgentToolCallExecutor.java:569–605` | **资源使用缺陷**：许可等待占用工具线程 |
| A3 | 先持有用户许可，再等待 Run 许可 | `AgentToolScheduler.java:15–17` | **准入组合缺陷**：同 Run 排队时占用所属用户配额 |
| A4 | 提交前开始计算超时，结果按提交顺序结算，超时 `future.cancel(true)` 并持久化可重试失败 | `AgentToolCallExecutor.java:575,610–701` | **契约边界**：本次不擅自改写超时/重试语义；但要记录待研究的“远端已执行，调用方认为超时”的 UNKNOWN 风险 |
| A5 | 有真实 PostgreSQL 的暂停恢复回归测试使用 `acquire()` 匿名子类定制单许可调度器 | `AgentPauseResumePostgresTest#queuedCallsBehindSemaphoreDoNotStartAfterPause` | **迁移约束**：测试必须保留同等/更强断言，不可删除来换绿 |
| A6 | Run Worker 独立池默认 2、允许 1–4，单轮最多 4 个工具 | `AgentRunExecutionConfiguration.java`、`AgentResourcePolicy.MAX_TOOL_CALLS_PER_TURN` | **性能解释边界**：当前默认吞吐影响未实测；不要承诺无条件 P95 提升 |

**范围限定**：配额是**单个 JVM 内**精确的 user/run 配额，不是跨实例全局分布式配额。若部署多实例，需另做 Redis/数据库分布式准入方案；不得在本任务声称集群级隔离。

## 2. 设计目标和非目标

### P0 硬目标

1. 不同 UUID 即使 `hashCode()` 相同，也有独立 `user=4` 与 `run=2` 配额；上限按真实 UUID，不按哈希桶。
2. 工具工作线程**不等待** user/run 许可；只有同时满足全局、用户、Run 配额的任务可提交给执行器。
3. 全局执行并发 **≤16**，调度器待运行队列 **≤64**（建议初始参数保持旧线程/队列容量的资源量级）；单用户 **≤4**，单 Run **≤2**。
4. 从准入、提交、取消、线程池拒绝、工作完成到清理都保持**一次占用/一次释放**，不能重复释放、不能遗漏释放；取消后仍未真正停止的任务继续占用执行额度。
5. 不破坏审批写路径、`knownInvocationResult`、`AgentLeaseScope`、暂停恢复 `PENDING`、结果依次持久化与 SSE 身份/次序。
6. 队列满拒绝保留现有 `TOOL_EXECUTOR_BUSY` 可重试错误的对外语义；过期任务**不得再开始执行**。
7. 不以吞吐/延迟猜测为验收结果。必须有同场景改造前后比较。

### 非目标

- 不重构 Agent Worker 调度、租约与持久化状态机。
- 不修改现有业务写工具授权、模型/provider、RAG 召回策略。
- 不引入 Redis 分布式配额、Kafka 等外部基础设施。
- 不在此阶段全面调整对“底层调用超时但可能有副作用”的状态表达；列入 P6-B 专项，当前禁止无依据宣称 `Future.cancel(true)` 等于远端取消。

## 3. 方案选择

### 候选对照

| 方案 | 好处 | 问题 | 结论 |
| --- | --- | --- | --- |
| ① 用 `ConcurrentHashMap<UUID, Semaphore>` 替换固定数组，仍在线程内 acquire | A1 可修，最小改动 | A2/A3 未解决；生命周期 map 回收需小心 | 不作为最终方案（可作基线） |
| ② 提交前依次 `tryAcquire(user)`、`tryAcquire(run)`，失败立即拒绝 | 无等待工作线程；实现简单 | 正常满配额被误判过载、无有界公平排队 | 不采用 |
| **③ 单 JVM 原子配额账本 + 有界调度队列 + 就绪派发** | 同时修 A1/A2/A3；有公平调度/取消回收；无外部依赖 | 状态和竞争处理更复杂，需大量确定性测试 | **推荐** |
| ④ 无限队列/虚拟线程等候许可 | 实现容易 | 内存背压/过期任务、真正的排队问题被掩盖 | 禁止 |

### 推荐结构

```text
模型轮次工具列表（至多 4/轮）
         │ submit(ctx, deadlineNanos, callable)
         ▼
┌──────────────────────────────────────────┐
│ AgentToolScheduler                      │
│  单锁保护的配额状态与任务状态机            │
│  精确 UUID → UserState / RunState         │
│  公平队列：user 轮转；同 user 的 run 轮转  │
│  pending 总数 ≤64                        │
│  同时原子校验/预占 global16,user4,run2    │
└────────────┬─────────────────────────────┘
             │ 仅就绪任务派发（绝不等待配额）
             ▼
     工具执行器（固定 16）
             │ 工具真实运行（含原权限、暂停/取消检查）
             ▼
       完成/中断真实退出
             │
             └── 释放全部额度、唤醒下一批就绪任务

AgentToolCallExecutor 等待各个 ToolHandle 并按原序持久化结果。
```

## 4. 调度器 API 和数据模型（建议）

以 `AgentToolScheduler` 作为唯一生产入口。建议引入 package-private 的泛型 `ToolScheduleHandle<T>`，暴露 `get(deadline)`、`cancel(mayInterrupt)`、`phase()`，由 `AgentToolCallExecutor` 将其包装为现有 `ToolExecutionResult`。不要把任务业务结果持久化搬进调度器。

```java
<T> ToolScheduleHandle<T> submit(
    AgentToolContext context,
    long deadlineNanos,
    Callable<T> task
);
```

调度器内部结构：

- `activeTotal: int`；`pendingTotal: int`（仅计未获执行额度任务）。
- `Map<UUID, UserState>`：按**UUID 全值**建立状态。`UserState.active`、待调度 Run 的 RR 队列。
- `Map<RunKey, RunState>`：推荐 `(userId, runId)` 组合身份；`RunState.active`、FIFO 等待任务队列。清理到空时移除 State。
- `Deque<UUID> usersRoundRobin`：轮转候选用户；同用户内部轮转 Run，避免某一个热门 Run 永久占配额。
- `ToolTicket`：`userId/runId/callId?`、`submittedNanos/deadlineNanos`、任务、完成句柄、线程引用（只在 RUNNING）、阶段状态。

状态：`PENDING → RESERVED → RUNNING → COMPLETED`；另外有 `REJECTED`、`EXPIRED_NOT_STARTED`、`CANCELLED_NOT_STARTED`、`CANCEL_REQUESTED_RUNNING`。**取消正在执行任务**只设置取消标志并可中断线程，额度必须等真实 `finally` 完成后才归还；否则无视中断的 HTTP 工具将突破并发上限。

## 5. 原子准入与生命周期细则

1. `submit` 先验证身份与截止时间；一个互斥锁内检查 shutdown、队列容量、插入等待项并调用 `drainReady()`。无等待的现成可运行任务允许直接进入 `RESERVED`；**永远不要因为别的用户被限流而让全局空闲容量闲置**。
2. `drainReady` 最多选择 16 个并发执行中的空槽；按用户 RR，再按 Run RR 寻找 eligible 项；同一个锁下**同时**验证并增加 global/user/run 计数，杜绝先拿 user 再等 run。扫描长度由 64 个待运行项自然有界。
3. 只有取得三层额度的任务才 `executor.execute`；不在执行线程调用 `Semaphore.acquire`。推荐工具池 fixed 16 + 有限 handoff queue（<=16、只吸收 worker 退出交接瞬间的竞态）；绝不让额外 64 个工具任务重复进入 executor 自带队列。
4. 派发后如执行器在关停/竞态中拒绝任务：**同一锁内回滚三层额度**，将句柄结算为未执行拒绝，并尝试派发其他等待项；不可遗留“RESERVED 但永不运行”。
5. 工具运行前在进入 `RUNNING` 时再次检查已取消/已过期；到时尚未开始者不执行工具。原有 `cancellation.throwIfRequested`、`repository.isPauseRequested`、`tools.checkPolicy` 和 `TOOL_CALL_STARTED` 的调用边界继续保持；不得将数据库访问放在调度器全局锁内。
6. 若任务仍 `PENDING` 时取消/超时：原子移出队列、更新 pending、标记 `CANCELLED_NOT_STARTED`（绝无工具副作用）。若已 `RESERVED` 但未 `RUNNING`，取消时也禁止启动；尝试 `ThreadPoolExecutor.remove` 清走尚未取出的已取消 Runnable，防止旧排队物污染执行器。
7. 若任务已经 `RUNNING`，`cancel(true)` 可以 `interrupt`，但**不能因此先释放 global/user/run 计数**；必须由工具 Callable 返回/抛异常后的真实 `finally` 释放。永不终止的工具仍占资源，这属于正确背压，不能假装空闲。单工具 I/O 超时和配额拒绝防护仍须存在。
8. `finally` 与所有非启动终态都精确释放一次，只在 active=0 且 pending=0 时回收 UUID 对应状态；关闭时拒新、结清等待项、向运行项发中断，不绕过已有数据库租约接管逻辑。
9. 维护任务截止时间 `deadlineNanos` 的**提交起算语义**（原实现 `System.nanoTime` 在 submit 前生成）：排队也消耗超时预算。到期不能被后续 `drainReady()` 启动；取消后不能重入。
10. 必须保证安全的结果句柄完成路径：无论正常、失败、取消、拒绝或关停，`AgentToolCallExecutor` 不得永远阻塞等一个不可完成 Future。

**取消竞争的重要实现提醒**：不可直接在 `FutureTask.done()` 或 `CompletableFuture.cancel()` 回调中无条件释放额度，因为运行中的 Callable 可能忽略中断。应由可观察的**真正执行结束**与取消前尚未执行两条互斥路径释放。

### 公平性定义

- 对**持续就绪**的不同用户，采用轮转派发，不保证操作系统线程调度层面的绝对先后。
- 同用户下不同就绪 Run 也轮转，避免一个 Run 连续占用用户的所有可用名额。
- 被自身 user/run 上限挡住的队列项可跳过，不得阻塞其他用户的就绪项（避免 HOL）。
- 对工作量、到达率超过队列容量的持续超载情况允许明确拒绝；不保证所有任务都会获准入。

## 6. AgentToolCallExecutor 集成契约

- **保持不变**：工具批量验证与权限校验；只读并行、写审批串行；`knownInvocationResult` 与恢复；模型工具调用身份；最终 `repository.recordToolResult` 顺序；SSE 事件不重排。
- **替换**：从 `scheduler.executor().submit(() -> { try(acquire(ctx)) { ... }})` 改为 `scheduler.submit(ctx,deadline,toolCallable)`。Callable 内保留已有取消/暂停/权限检查及工具执行；无需 Semaphore。
- 对队列满的 `REJECTED`：沿用 `TOOL_EXECUTOR_BUSY`（`retryable=true`），标识**工具未开始**。
- 对 `EXPIRED_NOT_STARTED`：不开始调用；建议保留 `TOOL_EXECUTION_TIMEOUT` 兼容错误码，同时增加仅供诊断的 `executionPhase=NOT_STARTED`（需确认现有序列化/模型显示契约，必要时本阶段只写安全指标）。
- 对执行中超时：先保留既有 `TOOL_EXECUTION_TIMEOUT` 对外处理，记录 `RUNNING_TIMEOUT` 度量；远端副作用不明时不能把“中断请求已发”记成“远端操作已取消”。**P6-B** 必须专项修正 UNKNOWN/幂等重试边界；若本阶段引入新状态必须配套完整数据库故障注入。
- 暂停优先级：工具尚未执行且已请求暂停时，调用的 invocation 必须保持 `PENDING`，不可因排队截止而悄悄写 FAILED。`AgentToolCallExecutor` 在队列超时、取消和结果收口路径应核对暂停意图，结清已准入调用后执行原有 `recordPaused`；须用真实 PostgreSQL 测试证明。
- 执行器不能直接持久化工具结果；数据库 fencing 仍由原 Worker/Repository 负责。
- 用 for-index 的结构代替 `futures.indexOf(future)`，绑定每个 `ToolScheduleHandle` 与 `ModelToolCall` 及其截止时间，以避免隐式依赖列表查找。

## 7. 观测指标（本阶段最低交付）

建议使用现有 Spring Boot Actuator / Micrometer，无须为本功能引入 Prometheus 依赖或放开匿名 `/actuator/metrics`。

| 指标（建议名） | 类型 | 口径 |
| --- | --- | --- |
| `agent.tool.scheduler.queue.wait` | Timer | 从 `submit` 接受到工具实际开始；只记录实际启动的任务；P50/P95 可由测试分布统计 |
| `agent.tool.scheduler.execution` | Timer | 工具 Callable 真正执行的时长（含其 I/O），不包含排队 |
| `agent.tool.scheduler.pending` | Gauge | 等待准入、尚未预占执行容量的任务数，0–64 |
| `agent.tool.scheduler.active` | Gauge | **真实仍占执行额度**的任务数，0–16；忽略中断的任务仍计入 |
| `agent.tool.scheduler.rejected` | Counter | 原因标签：`queue_full` / `shutdown` / `executor_rejected` |
| `agent.tool.scheduler.cancelled` | Counter | 阶段标签：`not_started` / `running_interrupt_requested` |
| `agent.tool.scheduler.timeout` | Counter | 阶段标签：`not_started` / `inflight` |

**指标维度红线**：不把 `userId`、`runId`、`invocationId`、任意 MCP 工具名当作 Micrometer tag（防高基数/隐私泄漏）。可用预定义 `kind=internal|mcp|controlled`、`outcome`、`reason`。每用户/Run 并发用受控测试断言或临时调试快照，不靠高基数公开指标。

## 8. 测试矩阵（CI 硬门禁与性能实验）

### 确定性测试（JVM / Mockito + CountDownLatch；尽量无 sleep）

| ID | 场景 | 硬断言 |
| --- | --- | --- |
| C01 | 构造不同 UUID 但 hashCode 相同的用户、Run | 配额不共享；不同用户均可按其自身上限开始 |
| C02 | 一个 Run 竞争 3+ 工具 | 同 Run 实际运行不超过 2；无额外工作线程被 `Semaphore.acquire` 占用 |
| C03 | 同用户多个 Run 竞争 | 总实际并发≤4，单 Run≤2，任一阻塞 Run 不预占其他 Run 的用户许可 |
| C04 | 多用户混合负载 + 热点用户/Run | 不满足准入的任务不会阻塞其他用户的就绪任务；轮转有界进展 |
| C05 | 全局并发/排队压力 | active≤16、pending≤64；额外提交明确 `TOOL_EXECUTOR_BUSY`，无假成功 |
| C06 | 取消排队中/已预占未开始/已开始任务 | 不再启动被取消项；真正未退出的运行项仍占执行额度；无负计数 |
| C07 | executor 真正 reject / 调度器 close / shutdownNow | 三层额度回滚，handle 必然终态，队列与 UUID 状态最终回收 |
| C08 | 截止时间已过的待运行任务 | 不启动工具；维护原结果记录一次，排队耗时有记录 |
| C09 | 10k 随机用户/Run 快速 churn（全部任务完成） | 退出后状态 Map 不随历史唯一 UUID 无界增长 |
| C10 | 指标 | queue/active/reject/cancel/timeout 口径和标签有界；无用户 ID 标签 |

### 集成回归（Testcontainers PostgreSQL）

| ID | 复用/新增 | 硬断言 |
| --- | --- | --- |
| I01 | 保留并迁移 `AgentPauseResumePostgresTest#queuedCallsBehindSemaphoreDoNotStartAfterPause` | 第一项已启动、后续等待；暂停后后者未执行且 invocation=PENDING；恢复后恰执行一次 |
| I02 | 既有 Agent Run 恢复/重试测试 | 工具已持久化结果不会再次执行；SSE 与结果配对不丢失 |
| I03 | 既有审批与 `ControlledWriteAgentTool` 集成测试 | 新调度器不能绕开审批；受控写工具执行效果不重复 |
| I04 | 新增排队取消/租约失效竞争夹具 | 旧 claim 不能借助等待任务成功落库；不会产生多余工具成功记录 |
| I05 | 全量 `mvnw verify` | Testcontainers 类真实跑过，失败不可通过跳过/放松断言掩盖 |

**性能对照分层**：
1. `REALISTIC`：Run Worker 分别 2、4；每轮≤4 工具；同/异用户、同/异 Run 组合，模拟可控的 I/O 阻塞。
2. `STRESS`：直接压调度器，构造超过 16 执行+64 等待的任务，证明背压与公平性，**不冒充真实产品负载**。
3. 改造前后同 JVM/JDK/CPU 限额/样本顺序/运行参数，至少三次独立重复；报告每种任务的 P50/P95、吞吐、拒绝率、最大 active/pending、CPU/线程状态及置信边界。不得用 CI 环境中的单次墙钟值宣称性能提升。

**门禁策略**：正确性和安全性为硬门槛；P95 优化只做量化报告，不预置没有基线依据的“至少提升 X%”目标。若真实负载无收益，要如实说明吞吐限制来自 Run Worker=2–4 或下游 I/O，而非强行扩大池/调模型。

## 9. 实施顺序 / 变更范围

### S0 — 测量基线（不修改生产调度）

- 为现有调度加临时测试测量适配器或仅测试侧桩；建立旧版真实配置负载与直接调度器压力对照。
- 固定 `dev` SHA、运行参数、线程数据，记录哈希碰撞和等待许可证据。

### S1 — 新的原子调度器与状态机

- `AgentToolScheduler`：单锁、精确 UUID 状态、有界 pending、RR、公平 dispatch、阶段感知取消/回收。
- 新增 `ToolScheduleHandle` / `SchedulerSnapshot`（仅 JVM 内必要诊断）等 package-private 类型。
- 新增 `AgentToolSchedulerTest`，先做到 C01–C10 全绿，仍不接业务执行器。

### S2 — Executor 最小切换

- `AgentToolCallExecutor` 接 `submit(ctx, deadline, callable)`；按原序落库与发 SSE；新任务仍走旧白名单/审批/取消/暂停校验。
- 修改原匿名覆盖 `acquire` 的暂停测试为可配置调度器（如 `maxRunConcurrent=1` 的测试构造器）；不能弱化真实 PostgreSQL 的 PENDING/继续断言。
- 跑所有 Agent 回归及 Testcontainers 关键类，完成 I01–I04。

### S3 — 可观测性和基线对照

- 接入上述低基数 Micrometer；权限跟随原 Actuator 安全基线。
- 两类负载对照，基于同指标出完整数量化报告，不凭设计保证性能提升。
- `mvnw -B -ntp verify`，如有前端改动则 `pnpm lint && pnpm typecheck && pnpm test && pnpm build`；代码/文档同步。

### 预期文件

- 修改：`ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentToolScheduler.java`
- 修改：`.../AgentToolCallExecutor.java`
- 新增（如需）：`.../ToolScheduleHandle.java`、`.../AgentToolSchedulerMetrics.java`
- 新增：`src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentToolSchedulerTest.java`
- 修改/扩展：`AgentPauseResumePostgresTest`、Agent 工具恢复/结果身份相关集成测试
- 长效设计：`docs/design/agent-tool-concurrency.md`（建议将本方案入库的路径）
- 一次性交付：`docs/archive/reports/p6-agent-concurrency-01-delivery-YYYYMMDD.md`
- 验收证据：`docs/acceptance-evidence/YYYY-MM-DD/p6-agent-concurrency-01/`

## 10. 风险、回退与交付验收口径

- **竞态风险**：cancel 与 dispatch/START、拒绝与占用回滚、执行完成与关闭冲突。所有计数在同锁下变更；RUNNING 的占用只由真实 callable 结束释放。
- **状态风险**：等待中的调用超时/暂停优先级与 `PENDING` 恢复行为。按原业务状态机保守处理，不能因为新队列改变持久化协议。
- **多实例风险**：单 JVM 账本只保证单进程精确限额。多实例公平性和全局配额留给独立任务。
- **观测风险**：Metrics 不能泄漏 UUID 或敏感工具参数；默认匿名访问规则不变。
- **回退**：禁止引入另一条无保护的并行执行通路。若要配置灰度，旧逻辑只能短期对照，并须说明旧路径有哈希碰撞；不要把旧模式当作长期安全兜底。回滚到旧 SHA 需在 PR/发布约束下人工决定。
- **交付前**：在 `dev` 形成原子提交，CI 的 Backend/Frontend/PR Image Job 全绿；**PR #18 仍须 Draft、禁止自动合并到 main**，除非用户另行明确批准。

### 完成条件

同时满足：C01–C10、I01–I05 通过；与旧代码可复现的确定性对照；无新数据库迁移；无审批/租约/暂停/取消回归；指标低基数且收口；两组负载前后对照如实报告；最后报告给出目标 SHA、CI 链接、未执行场景、残余风险及必要的 P6-B 交接。

## 11. 给实现 Agent 的执行约束

> 这是设计，**不是完成报告**。实施不得以改动测试、跳过容器用例或人工断言替代真实并发事实。不得触碰现有 `.env`、真实凭据、数据库生产数据或 PR #18 合并操作。先验证源码现状与本设计的兼容性；若发现设计与代码契约冲突，写明差异与最小调整理由，再实施。不接受仅替换哈希数组、不解决工作线程排队的半成品。
