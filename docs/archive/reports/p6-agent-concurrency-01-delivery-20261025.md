# P6-AGENT-CONCURRENCY-01 交付报告（2026-10-25）

## 交付与复核边界

实际基线 dev/origin/dev：`8ee1cebbf43ab3c1b06a4982d3f3d4ad70ec60ba`；main/origin/main：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`。
开工工作区干净，fetch后设计假设全部成立。生产修改只涉及调度器和工具批次执行器，无数据库迁移、前端业务修改或依赖变更。
PR [#18](https://github.com/BangShou1st/ai-collab/pull/18) 开工时 OPEN / Draft / 未合并。

本报告、设计、源码及本地证据一起提交到 dev。最终提交本身的 SHA 无法自包含于自身文件；完整最终 dev SHA、远端一致性、同 SHA Actions run/job/步骤核对及最终裁决由聊天交付回执附在本报告之后。
最终 CI 复核以前不得仅凭本报告宣称 ACCEPTED。可独立用交付回执 SHA 检出本报告，核对 [源码 blob 清单](../../acceptance-evidence/2026-10-25/p6-agent-concurrency-01/source-manifest.json) 与 [全部证据](../../acceptance-evidence/2026-10-25/p6-agent-concurrency-01/README.md)。

## 已实核对的实现版本 CI 回执

实现提交：`9783a0fb2e5e51c2af8257a540fb1f3b0c9626cc`，已同步 origin/dev。
[dev push CI](https://github.com/BangShou1st/ai-collab/actions/runs/40113819565)：Backend/Frontend通过，Image按dev push既定条件跳过。
[PR #18 CI](https://github.com/BangShou1st/ai-collab/actions/runs/40113822547)：Backend/Frontend/Image全部通过。
两轮运行元数据的head_sha均为上述实现提交，已核对实际日志而非只看job名称。

- Backend：确实执行 `bash ./mvnw -B -ntp verify`，1706项、0失败/错误、34显式opt-in跳过。
  AgentToolSchedulerTest=13/0跳过，AgentPauseResumePostgresTest=21/0跳过，PersistedModelTurnRecoveryPostgresTest=24/0跳过，
  LegacyApprovalCitationUpgradeTest=1/0跳过；日志明确记录 `Container test guard passed for all required classes`。
- Frontend：安装冻结lockfile、lint/typecheck/test/build均执行，51文件393项测试通过。
- Image：实际Docker构建和容器冒烟执行成功，敏感Actuator env/metrics/info均401。
- PR实际checkout为`a91b7f42efe82cf91acdea6ac45e034c563670dd`，合并9783a0f到未改动main的校验提交；
  其Git tree与实现提交完全相同，均为`168027e41f42f15317d3f4e9f3842ac62fbe4edd`。
- main/origin/main仍为`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；PR #18仍OPEN、Draft、未合并；工作区干净，临时任务日志已清理。

此补记只修改交付报告，不修改生产/测试或原始证据。本报告所在最终文档提交的完整SHA与再次CI结果，仍在聊天最终回执中给出，避免把已验证实现SHA误称为之后的文档提交SHA。
## S0：旧缺陷与实验基线

修改生产代码前实际运行：`AgentToolSchedulerBaselineTest#collisionAndWorkerStarvation`，1/1通过（测试断言的是缺陷复现），原始输出见 s0-baseline.txt。
两个不同 UUID `new UUID(0,0)` 与 `new UUID(1,1)` 的 hashCode 相同。热点 Run 投入16项时仅2项工具实际执行，14个工作线程阻塞在 Semaphore，独立请求未能启动；executor等待64项后明确拒绝1项。
保留基线源码的测试适配器 LegacyToolScheduler，又验证同用户热点 Run 两个实际执行、两个等待Run许可的任务占满4个用户许可，兄弟Run超时，其他用户可执行。
该复现通过闭锁、Semaphore实际排队数量、Future有界等待断言，不通过删除测试或把错误当成功完成。

S0–S3 对照测试保留旧实现于测试源码，新版生产路径没有回退到旧哈希调度的开关。

## S1：实现、状态与安全不变量

一个锁保护精确 UUID 用户账本及每用户 Run 账本，LinkedHashMap末尾轮转等价于用户RR + Run RR；同Run等待FIFO。
选择就绪任务时同时检查并预占 global16/user4/run2，等待容量64；不就绪Run不预占用户额度，不阻塞其他用户。
执行池固定16线程，额外16容量交接队列仅存已预占项；等待任务只存在调度器，身份状态总量由active+pending界定。
状态：PENDING→RESERVED→RUNNING→COMPLETED；REJECTED/EXPIRED_NOT_STARTED/CANCELLED_NOT_STARTED为未执行终态。
RUNNING取消转CANCEL_REQUESTED_RUNNING，Future可立即取消，但三层执行额度只由Callable真实退出的finally释放。

所有预占/回滚/取消/开始/完成使用同锁，runner身份检查和interrupt请求也在锁内，防止中断已被复用的线程。
预占未启动取消可从真实ThreadPoolExecutor移除，已经取出的包装任务进入时看到终态直接返回；同一占用只释放一次。
拒绝提交回滚三层计数；关闭拒绝新任务、结清等待/预占、请求中断运行项。忽略中断的调用继续占额度，资源耗尽时正确背压。

单个daemon截止线程维护至多active+pending个定时项；开始/结束/取消移除对应定时项，不缓存历史UUID。
未开始项截止后主动结算TimeoutException且禁止再启动，运行项由业务调用方原有绝对截止等待收口。
10k独立用户/Run执行完后，active/pending/users/runs/alarms均归零。

## S2：业务契约与竞争

AgentToolCallExecutor 使用 ScheduledToolCall 显式绑定工具调用、绝对截止与Future，删除 futures.indexOf。
任务提交前计时，等待消耗原工具预算，结果仍按模型原顺序记录；knownInvocationResult、审批入口、SSE调用ID及权限校验未改。
同步队列拒绝及异步executor拒绝均为TOOL_EXECUTOR_BUSY、retryable=true。提交阶段与结果阶段共同受finally保护，异常时取消本批未完成句柄。
取消优先于暂停；等待超时先在调度器锁内终止未开始项，再查取消/暂停意图。暂停已请求且任务未开始时保留invocation=PENDING，结清已执行结果后PAUSED。
真实PG验证：q1执行后暂停，q2/q3保持PENDING，恢复按原身份各执行一次；另使q2/q3确定到期后再结算，同样保留PENDING与恢复零重复。
真实PG取消竞争验证q2/q3未执行，调用按原语义CANCELED，无成功结果；旧claim在等待时失效后，原fencing拒绝结果落库，三项保持PENDING，无成功结果。
旧claim的只读出站准入与跨线程ThreadLocal传播没有扩展为新的分布式协议；本阶段保证现有持久化fencing不被绕过，不承诺接管瞬间所有已派发I/O都已停止。

## 测试矩阵

| ID | 实际覆盖 | 结果 |
| --- | --- | --- |
| C01 | c01ExactCollisionIsolation：碰撞用户+碰撞Run，8个精确独立预占 | PASS |
| C02 | c02c03AtomicRunAndUserAdmission：热点Run只派发2项 | PASS |
| C03 | 同测试：热点不提前占用户额度，其他Run填满user4 | PASS |
| C04 | c04RoundRobinUsersAndRunsWithoutHeadBlocking + C05满队列就绪旁路 | PASS |
| C05 | c05CapacityAndReadyBypassFullPending：active16/pending64/明确拒绝 | PASS |
| C06 | pending/reserved取消、300次取消dispatch竞争、运行项忽略中断 | PASS |
| C07 | 真实executor拒绝、close未开始/运行任务、实际退出后回收 | PASS |
| C08 | RESERVED与PENDING主动截止回收，禁止工具执行 | PASS |
| C09 | c09TenThousandIdentitiesReclaimed：10k churn后五项快照全0 | PASS |
| C10 | gauges/timers/counters/固定标签，inflight timeout单计数 | PASS |
| I01 | AgentPauseResumePostgresTest：原单许可暂停测试迁移+排队过期暂停竞争 | PASS |
| I02 | PersistedModelTurnRecoveryPostgresTest及全量恢复/SSE回归 | PASS |
| I03 | AgentPauseResumePostgresTest规划受理恢复、LegacyApprovalCitationUpgradeTest真实PG审批幂等及审批单测回归 | PASS |
| I04 | queuedCallsCannotPersistAfterCancellationOrLeaseLoss：真实PG取消/旧claim两组闭锁竞争 | PASS |
| I05 | 完整bash ./mvnw -B -ntp verify；容器类真实执行 | PASS：1704项，0失败/错误，34显式opt-in跳过，关键容器类跳过0；最终补充2个单测另跑 |

本地测试汇总见 local-tests.json。真实模型/外部环境opt-in类未执行，不用于替代容器测试；benchmark在独立命令显式启用。
前端虽无业务改动，仍按仓库清单执行lint（0错误/75既有警告）、typecheck、51文件393项测试、build，全部通过。

## S3：性能与观测

条件：Windows11、Oracle Java21.0.10、32可见逻辑核，未改变CPU配额；同JVM交替old/new，每条件3次新调度器实例。
REALISTIC分别2/4个Worker，每Run每批4项、10批，每项模拟4ms I/O；覆盖同用户、多用户。STRESS一次112项、前16项热点Run，先闭锁提交再放行。
原始每项延迟、15组成对（30次单版）试验数量见CSV；延迟从提交至实际结束，拒绝不计成功延迟。吞吐按成功完成/整次墙钟，拒绝率=拒绝/提交。

| 条件 | 旧 P95 ms（3次） | 新 P95 ms（3次） | 旧/新吞吐范围（项/s） |
| --- | --- | --- | --- |
| 同用户 Worker2 | 10.490 / 10.174 / 10.854 | 10.288 / 11.308 / 10.928 | 733–784 / 759–789 |
| 同用户 Worker4 | 19.887 / 20.812 / 22.229 | 20.641 / 20.203 / 20.177 | 798–817 / 778–799 |
| 多用户 Worker2 | 10.701 / 11.051 / 10.650 | 18.714 / 10.493 / 10.394 | 784–808 / 679–785 |
| 多用户 Worker4 | 10.576 / 10.301 / 11.047 | 10.841 / 10.552 / 10.676 | 1563–1627 / 1497–1561 |
| STRESS | 43.953 / 47.236 / 43.364 | 48.434 / 48.200 / 46.534 | 1601–1772 / 1357–1423 |

P50各次原值在summary.csv；STRESS旧29.507–31.922ms、新16.749–17.190ms。所有REALISTIC拒绝率0，实际全局峰值同用户4、多用户Worker2=4/Worker4=8。
STRESS两版均80完成/112提交、32拒绝（28.571%）、实际执行峰值16、待队列峰值64；释放前Semaphore等待线程旧12/14/14、新0/0/0。
旧版pending列只统计executor等待队列，不包含已进入worker但等Semaphore的任务；新版为全部等待准入项，因此不能直接把两列相减当延迟收益。

结论：正确性目标达成；真实负载没有一致P95改善，STRESS中位延迟降低但尾延迟/总吞吐反而较差。
新设计付出锁内调度、截止定时和观测开销，热点尾部仍受run2约束，Worker2–4与下游I/O限制真实产品并发。
仅三次短时合成试验且无独立JVM/长时间稳态/置信区间，不能据此宣称生产性能提升。process_cpu_ms是进程CPU差值，0受Windows计时精度影响。

| Metric | 类型 | 口径与标签 |
| --- | --- | --- |
| agent.tool.scheduler.queue.wait | Timer | 接受到包装Callable实际启动，只记真正启动项，无标签 |
| agent.tool.scheduler.execution | Timer | Callable真实I/O时长，取消后至实际退出仍计入，无标签 |
| agent.tool.scheduler.pending | Gauge | PENDING未预占数0–64，无标签 |
| agent.tool.scheduler.active | Gauge | RESERVED+RUNNING尚未退出数0–16，无标签 |
| agent.tool.scheduler.rejected | Counter | reason=queue_full/shutdown/executor_rejected |
| agent.tool.scheduler.cancelled | Counter | phase=not_started/running_interrupt_requested |
| agent.tool.scheduler.timeout | Counter | phase=not_started/inflight |

超时请求取消可同时增加timeout和cancelled，自动未开始到期只计timeout；同一Ticket超时不重复计数。
Spring注入现有MeterRegistry，无新增Prometheus或Actuator匿名放行。无userId/runId/invocationId/工具名/Prompt/密钥标签。

## 文件清单

生产：`AgentToolScheduler.java`、`AgentToolCallExecutor.java`（均在backend agent/application/runtime）。
测试：同包`AgentToolSchedulerTest.java`、`AgentToolSchedulerBaselineTest.java`、`AgentToolSchedulerBenchmarkTest.java`、`LegacyToolScheduler.java`新增；`AgentPauseResumePostgresTest.java`修改。
设计：`docs/design/agent-tool-concurrency.md`新增并保留输入方案/状态机/取舍/矩阵；`agent-runtime-design.md`与`agent-runtime-maintenance.md`同步。
CI：`.github/workflows/ci.yml` 增加调度器/暂停/结果恢复/真实PG审批的必须执行类检查，防止整类跳过。
导航：`README.md`与`docs/README.md`。本报告位于`docs/archive/reports/`，全部证据位于`docs/acceptance-evidence/2026-10-25/p6-agent-concurrency-01/`。

## 未执行、残余风险与P6-B交接

- NOT_EXECUTED：真实外部Provider/MCP网络取消确认、真实模型质量/浏览器交互验收（本任务无UI改动）、多实例分布式配额、生产长时间稳态性能。
- 运行中的网络调用可能忽略中断，甚至远端已经产生受控规划等副作用；Future取消/TOOL_EXECUTION_TIMEOUT不能证明远端撤销。
  P6-B需按稳定invocation/业务操作身份核对已提交结果、UNKNOWN表达与重试幂等，配套真实PG故障注入，不能只改调度Future完成状态。
- 单JVM精确配额不是集群全局限流；多副本需独立设计。永久不退出工具会持续占额度，这是正确背压，需下游I/O超时与运维处置。
- 截止time与业务持久化原协议维持兼容，未引入新数据库状态。只读工具在claim接管瞬间的晚出站仍交由P6-B核对，持久化fencing已回归。
- CI最终裁决、完整SHA、推送状态、main未变与PR仍Draft以本报告后的同SHA交付回执为准；禁止自动合并或改Ready。
