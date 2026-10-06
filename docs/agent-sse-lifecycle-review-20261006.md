# 统一 SSE 发送后的生命周期复核

日期：2026-10-06。分支 `codex/context-foundation`，审查基线 HEAD `47650b6`，包含 `0a20d6c` 后端统一发送及 `ac644fe` 前端 S2/S3。本次只审查、执行受影响正式测试与受控失败探针；未修改生产代码、提交、推送、启动服务/浏览器/容器、调用真实模型或操作数据库。`.freebuff/` 未动。

## 1. 结论与边界

**统一发送方向成立，原 S1 的调用线程隔离已补齐；还有两处可复现的异常/连接生命周期缺陷，应限定补修后结束这一批。** 不需要更换 Agent 编排、引入框架或新消息平台。

已经核实的部分：

- `publish`、正文预览、heartbeat 与初始 replay 的显式 `emitter.send` 都归到订阅单飞发送任务，模型读流、事务 afterCommit、scheduler 只投递意图；共享线程池为 4 worker、128 队列，默认 AbortPolicy，不走 CallerRuns。
- 持久事件按 `AgentEventRepository.list` 的 `sequence_no > cursor ORDER BY sequence_no` 追赶。仓库在同一事务内更新同一 run 行分配序号并插入事件，同一 run 的提交被行锁串行化；因此已提交较大序号不会越过尚未提交的较小序号。按数据库顺序追赶能解决 afterCommit 唤醒乱序。
- 把原未打桩仓库替身改成模拟真实游标读取，是与实现契约对齐，不能据此判定为放宽断言。
- 临时帧不落库、不推进持久游标；现有单槽累计快照、8000 字符限额、S2/S3 前端修复保持。

以下两项均为 P2：异常或特定并发时序下影响可靠性，应在下一批限定修复。其影响不同于原来的业务线程被网络同步阻塞，不把原 S1 重新判为未实现。

## 2. L1：读取失败后会立即自我重排，形成无间隔重试

位置：`AgentEventStreamService.deliver` 第 179、189–200 行；heartbeat 的标记只在 `sendHeartbeat` 第 265 行清除。

触发顺序：一次 heartbeat 把 `heartbeatPending` 置 true → `catchUpDurable` 读取仓库抛 RuntimeException → 还没执行 `sendHeartbeat` → catch 仅记录日志 → finally 看到 heartbeat 仍待发，立刻重新 schedule → 再次读库失败并重复。

代码注释说“等待下一次事件或 heartbeat 周期”，实际没有等待。持续且快速返回的读取错误会反复消费发送任务和数据库调用；如果多个订阅同时如此，可挤占共享发送能力。此处只实测了重排链条，没有把真实数据库压力或全系统停滞写成已实测结果。

受控探针 `reviewReadFailureMustWaitForAnotherExternalWakeupInsteadOfRetryingImmediately`：先完成初始空 replay，再只调用 **一次** heartbeat；仓库前 3 次读取抛瞬时数据访问异常、第 4 次返回空（避免探针本身无限循环）。连续两次运行都输出：

```text
SSE_LIFECYCLE_READ_ATTEMPTS_FOR_ONE_HEARTBEAT=4
expected: 1, but was: 4
```

### 最小修法

在原发送器内明确读取异常的退出语义。可以沿用“保留订阅，等下次外部事件/heartbeat”的策略，但异常退出不能因本轮未清标记立即自我重排；也可以结束该订阅，让已有客户端重连策略恢复。选一种，保证正常并发投递不丢唤醒、游标不越过未成功写出的事件。

不新增 provider 重试、重试配置平台或额外线程池。正式回归需验证：一个外部唤醒失败后不持续读库；恢复仓库并再次外部唤醒（或重连）能从原游标接着送；错误只影响该连接，不推进 Agent 运行状态。

## 3. L2：旧订阅摘除与新订阅登记不是同一个原子操作

位置：`AgentEventStreamService.subscribe` 第 101–102 行与 `remove` 第 290–293 行。

触发顺序：

1. 旧订阅移出列表，`isEmpty()` 得到 true。
2. 同一 run 的新连接通过 `computeIfAbsent` 取得这个列表并加入新订阅。
3. 旧清理执行 `subscriptions.remove(runId, list)`，把已经含新订阅的列表从 map 中删掉。

新订阅自己的初始任务仍可能完成 replay，但后续 publish、正文与 heartbeat 都遍历不到它。`SseEmitter(0L)` 没有该层的超时，当前前端 reader 也没有心跳超时重连判断，页面可能保持连接却不再更新。这直接影响刷新/重连体验。

这是继承的列表生命周期竞态：`a6eadd6` 中已有同样的登记/摘除结构，不能写成 `0a20d6c` 新引入的缺陷；本轮既已收拢发送生命周期，应在此处一并限定修正。

受控探针 `reviewRemovingOldSubscriberMustNotDetachConcurrentNewSubscriber`：用 latch 停在“已经观察为空、尚未删 map”的窗口，期间调用真实 `subscribe` 登记新连接，再放行旧清理。连续两次运行都输出：

```text
SSE_LIFECYCLE_NEW_SUBSCRIBER_RETAINED=false
expected true, but was false
```

### 最小修法

让同一 run 的“取/建列表并加入订阅”与“移除订阅并判断是否删列表”在同一个 map key 下原子串行化，例如登记用 `compute`、摘除用 `computeIfPresent`，把列表变更放进各自的回调内部。回调只操作短小元数据，不做网络、数据库读取或等待。

**只改摘除而仍把 `.add(subscription)` 留在 `computeIfAbsent` 外面不够**：新连接仍可拿到随后被摘除的旧列表，再往脱离 map 的列表加入。

正式回归要验证新订阅最终仍在发送范围，并能收到随后提交的事件；最后一个订阅关闭后正常清理，重复旧回调不摘掉新连接。归档探针刻意冻结旧实现窗口，转正式测试时应调整线程协调：不要固化“同步 subscribe 必须在旧清理放行前完成”或“仍使用旧列表实例”的假设，否则正确的原子串行化会被测试自身阻塞。

## 4. 本次证据

实际复跑命令（后端目录）：

```powershell
.\mvnw.cmd -o '-Dtest=AgentContentPreviewStreamIsolationTest,AgentContentPreviewPublisherTest,ModelContentPreviewObservationTest' test
.\mvnw.cmd -o '-Dtest=ScratchSseLifecycleReviewProbeTest#review*' test
```

- 正式测试 **21 项通过**：隔离 10、预览发布 5、观察上下文 6，0 失败/错误/跳过。
- 两个新增探针 **2 项失败、0 错误**；重复一次结果相同，计数为一次 heartbeat 触发 4 次读库、新订阅未保留。
- 核对了交付日志 `ai-collab-backend/target/full-test-unified-sender.log`：1172 项、0 失败/错误、11 跳过、BUILD SUCCESS、FULL_EXIT=0。该全量是执行会话证据，本次没有重跑全量。全量通过与新边界探针失败可以同时成立。
- 本次没有重跑前端、类型检查、浏览器或真实模型；218 项前端结果为此前交付证据。

源码探针与原始 surefire txt/XML、三套正式测试结果归档于：

`docs/acceptance-evidence/2026-10-06/real-use-streaming-review/lifecycle/`

探针 `.java.txt` 在证据目录，不作为正式测试。审查结束后删除本次临时源文件、编译 class 与 target 中的探针 surefire 结果，避免后续全量误计失败记录。

## 5. 下一批有限任务与收口标准

先只补 L1/L2，并把上述边界变成正式回归。保留三个已有本地提交，沿既有 sender 职责修改，不重做 S2/S3、检索、暂停恢复、模型适配或计费。

修后复跑 sender/预览/控制器游标相关受影响测试，确认原来的慢连接隔离、顺序、终态关流、池饱和恢复和关闭守卫都保留。完成后做一次后端全量；前端不改则无需重复浏览器、真实模型或长对话。清楚记录跳过门控与实测范围。

真实 HTTP 的 `Last-Event-ID` 断线重连验收有价值，但当前先修已复现的两项；它是进一步的传输证据，不能替代竞态回归。若随后执行，只需隔离测试运行、生产 SSE endpoint 与已持久事件：收到 1/2 后断开，断线期间提交 3/4，用 Last-Event-ID=2 重连，验证 3/4 按序补回、后续实时事件与终态关流正确，临时正文不改持久游标。不依赖真实模型，不扩大成新恢复功能或浏览器全流程。

达到“两处缺陷修复、正式回归覆盖、既有发送契约保留”就结束这一批，不以新增优化建议不断延长收口。

## 6. L1/L2 补修收口记录（执行会话，2026-10-06）

承接第 5 节，只修 L1/L2 并转正式回归，未改 S2/S3、检索、暂停恢复、模型适配或计费；保留了统一发送器结构。

### 6.1 修法（AgentEventStreamService）

**L1（无间隔重试）**：订阅新增 `wakeupGeneration`（AtomicLong），所有外部唤醒（publish/正文/heartbeat/初始 replay）统一经 `wake()` 推进代次再调度；`schedule()` 被 sending CAS 挡住时也推进代次，让在跑的一轮在退出时看到“有新唤醒”并补排（此前的唤醒会因此丢失，是补修中发现并一起修掉的第二处丢失点）。`deliver` 记录进入时的代次：正常退出仍按原规则兜底补排；异常退出仅当代次变化才补排——快速返回的读取错误不再无间隔自我重试，未清标记留给下一次外部唤醒，游标不越过未成功写出的事件。选中“保留订阅等外部唤醒”而非“结束订阅走重连”：注释与文档原描述一致，且不需要客户端重连。

**L2（登记/摘除竞态，继承缺陷）**：`subscribe` 改用 `compute(runId, …)` 在 map key 上原子加入；`remove` 改用 `computeIfPresent(runId, …)`，在同一个 key 的原子回调里完成“移除订阅 + 判空删条目”。“观察空列表”与“删条目”不再可分，旧清理不会吃掉并发登记的新订阅；重复旧回调幂等无害。

### 6.2 正式回归（AgentContentPreviewStreamIsolationTest 10 → 12 项）

- `failedDurableReadWaitsForNextExternalWakeupInsteadOfRetryingImmediately`：初始空 replay 后只给一次 heartbeat，仓库前 3 次读取抛瞬时异常，断言恰好读库 1 次（旧实现读 4 次）；随后恢复仓库并 publish，订阅从原游标补回事件——失败不拆连接、不丢订阅。
- `removingAnOldSubscriberDoesNotDetachAConcurrentlyRegisteredNewSubscriber`：插桩 map 冻结在二参 `remove(key,value)` 内部——只有旧实现的“观察空列表→删条目”会走到这里，修好的实现走 `computeIfPresent`，钩子永不触发，不阻塞正确的原子串行化（遵循第 3 节告诫）。窗口内并发真实 `subscribe`，断言新订阅留在 fanout 表、能收到后续持久事件（用游标推进观察，因 subscribe 内部自建真实 SseEmitter）、重复旧回调不摘新订阅、最后一个订阅关闭后条目正常清理。
- **红绿验证**：回退 L2 为旧实现后该测试确定性失败（窗口进入且新订阅被摘除）；恢复修复后连跑 3 次稳定全过。此前两次“回退后仍绿”是回退脚本静默未生效（文件未真正改回），不是红测失败；改用编辑工具回退后才得到真实红测。
- 测试替身口径：仓库替身统一模拟 `sequence_no > afterSequence` 升序游标语义（与第 1 节已核实的实现契约一致）。发现并修正了两处替身不忠实：① 无状态 `thenReturn` 每次返回同一已发事件，违反 list 契约，会让追赶循环在 forked JVM 里无界自旋分配——这正是本轮全量两次 OOM 的根因（修复后套件耗时从 4.5–9s 降至 2.8s，OOM 消失）；② L1 恢复阶段的替身曾同样无状态，已改为游标过滤。生产 `list` 契约保证 `seq > cursor`，无需为此增加防御代码。

### 6.3 本轮实测

| 范围 | 结果 |
| --- | --- |
| `AgentContentPreviewStreamIsolationTest` | 12 项全过（连跑 3 次稳定；含红测验证） |
| 受影响套件（controller / preview publisher / coordinator / worker / retry / approval） | 全过 |
| 后端全量 `mvnw test`（`ai-collab-backend/target/full-test-lifecycle-fix.log`） | **1174 项：0 失败 0 错误，11 显式 opt-in 跳过，BUILD SUCCESS（3m43s，FULL_EXIT=0）** |

环境说明：复核会话结束后 Docker Desktop 未随重启拉起，第一次全量 285 项 Postgres 集成测试被门控跳过——已用 Docker Desktop 主程序重新启动（未用服务命令），重跑后仅余 11 项显式 opt-in 跳过。中间两次全量失败（`OutOfMemoryError: Java heap space`）根因即上文替身不忠实导致的追赶自旋泄漏，修正替身后消失，未改生产代码。前端本轮未改，无需重跑。

### 6.4 边界结论

L1/L2 修复后：读取瞬时失败不再挤占共享发送线程（一次外部唤醒恰一次读库），恢复后从原游标续传；并发登记的新订阅不再被旧连接清理孤立，能正常接收后续事件直至终态关流。统一发送、按游标追赶、终态顺序、池饱和恢复、关闭守卫等既有契约全部保留。第 5 节的真实 HTTP 断线重连验收仍为后续可选项。
