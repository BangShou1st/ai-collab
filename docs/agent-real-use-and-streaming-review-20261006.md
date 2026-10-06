# 真实使用与正文流式审查（2026-10-06）

基线：分支 `codex/context-foundation`，HEAD `a6eadd6`，开始审查时工作区干净。核对了四个本地提交、生产调用链、相关正式回归和归档证据。本次没有修改生产代码，没有启动服务、浏览器或容器，没有调用真实模型或操作数据库；新增内容仅为本审查及失败探针证据，未提交。

## 1. 结论

**A 真实文档检索成功链可以收口；B 正文流式主体已完成，仍需一次限定补修。** 当前方向正确，不需要框架迁移或再做整体维护。

已确认三个局部问题：一个执行可靠性问题、一个预览生命周期问题、一个控制标记展示问题。下面列出的范围一次补齐后复核，不重新打开已完成的恢复、暂停、自动重试或 RAG 任务。

## 2. A 与已完成部分的核对

- `q2-search-output.json` 与 `q2-messages.json` 的检索来源和回答能对应：周三 02:00–02:30 发布窗口、15 分钟回滚时限；回答声明只读取提纲和相关摘录，不冒充全文。恢复 embedding 服务后的成功链有实际证据，未发现需要重新设计检索的问题。
- 现有 Zen 流式正文确实进入了临时 SSE；modelCallId 与请求内 revision 关联、持久游标不被临时帧推进、完整结果继续走原落库及消费入口，方向成立。
- 跨线程 `capture` 后显式推送的修复确实存在，相关正式测试通过。没有要求为了本轮补修再替换这套通道或改所有 provider。
- 支持范围可以保持 Zen；其他路径一次性完成展示如实说明即可。合成 UI 与真实 SSE 是不同证据，报告已分开记载，不要求为截图窗口重跑真实问答。

证据数字需就地纠正：`sse-frame-real-model-final.json` **全运行共 51 个正文帧**，其中较早一轮 3 帧，最终回答轮 **48 帧 = 47 个增量 + 1 个 final**，最终轮字符数 14→3159。真实增量结论不变；不要把 51 帧全部归到最终轮。

## 3. S1 [P1] 临时 SSE 写阻塞进入了模型请求超时链

位置：`ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentEventStreamService.java:76`，直接发送在第 81 行。

实际链路：模型读流线程 → adapter 的 `ModelContentPreview.push` → `AgentContentPreviewPublisher.onContent` → `publishContentDelta` → 同步 `SseEmitter.send`。`JsonHttpModelClient.stream` 的 worker 在等待整个读流任务完成，并对这个任务执行请求 deadline。捕获 RuntimeException 只处理异常，不能隔离阻塞。

**确定性复现**：使用真实 `JsonHttpModelClient` 与生产 Zen adapter，HTTP transport 替身立即提供有效正文和 `[DONE]`，订阅写入用 latch 阻塞。同一份响应无预览时成功，加入预览订阅后返回 `AI_MODEL_TIMEOUT`。这证明可选展示层能把已经准备好的模型响应变成超时，随后协调器会按既有策略进入自动重试。探针未等待网络，也未调用真实模型。

限定修法：

- 模型读流回调只做有界、非阻塞的临时发布，不能等待客户端网络写完成。
- 复用现有 SSE 服务，选择小而明确的有界发送方式；累计快照允许合并/丢弃旧临时帧，压力或订阅故障时舍弃预览或断开对应订阅，持久事实仍靠数据库及 replay 恢复。
- 考虑同一 SSE 连接的写入串行性；不能只把阻塞搬到另一个任务、再在读流线程等待 Future。慢订阅者不能通过共享写锁或队列反过来卡住模型读取。
- 不新增无限队列、每 token 线程/任务、每运行线程池或 Redis 消息平台；不改 provider 重试、模型 deadline、运行额度或数据库事件序号。
- 累计预览在服务端也应有明确体积边界，前端 8000 字截断并不能限制服务器继续发送越来越大的全文快照。复用现有正文展示上限即可，完整模型结果保持原处理。

正式回归至少锁定：阻塞一个预览订阅时，立即到达的模型完整响应仍能成功返回；发布失败不导致模型重试；关闭/丢弃临时发送不遗漏持久事件或推进持久游标。

## 4. S2 [P2] 预览没有遵循已接受事件与请求关闭边界

位置：`ai-collab-frontend/src/modules/agent/use-agent-workspace.ts:473` 与 `:161`。

`applyAgentEvent` 会拒绝旧序号、重复序号及其他运行事件，但调用方随后无条件调用 `applyPreviewEvent`。另外，`MODEL_COMPLETED` 只把预览标为 finalized，`applyContentFrame` 仍然接收同一 call 的更高 revision。

三个组件级探针全部失败：

1. 当前已有正文，重复同序号 `MODEL_STARTED`：权威时间线不变，预览却被重新置空。
2. call-2 正在生成，旧 call-1 的完成事件再次到达：时间线拒绝旧事件，预览却被清掉。
3. 纯文本轮 `MODEL_COMPLETED` 已提交、尚待最终消息加载：同 call 的迟到正文帧可以改写已定格的正文。

这些是对事件重放与迟到帧的确定性组件输入验证，不冒充真实浏览器网络复现。

限定修法：

- 只有当前运行中**本次被时间线接受**的持久事件才推进预览生命周期；复用同一接受判断，不建立另一套事件排序规则。
- 用 modelCallId 与请求关闭状态共同判断是否接收临时帧。已关闭请求可以保留现有显示文字等待最终消息替换，但不能继续接收新增正文。
- provider 的 final 帧只表示输出结束，不等于持久化或业务成功；显示光标状态与持久完成状态保持明确含义。
- 保留现有切页面/运行保护、最终回答一次、等待消息时不闪烁、失败重试新 call 不混旧文字等行为。

将上述三个探针转成正式回归，另用既有测试确认正常增量、暂停/失败及纯文本最终收口不退化。无需增加通用前端请求框架。

## 5. S3 [P2] 澄清控制标记被当成正文直接展示

位置：`ai-collab-frontend/src/modules/agent/AgentView.vue:212`；预览接收入口 `use-agent-workspace.ts:166`。

系统提示要求模型用 `[QUESTIONS]` 前缀提出澄清，后端依赖它分派到 WAITING_FOR_USER_INPUT。当前预览直接展示累计原文，所以合法澄清响应 `[QUESTIONS]\n请确认本周的统计范围。` 会把内部标记显示给用户。组件探针已确认失败；这不是 Markdown/XSS 问题，而是遗漏普通正文展示边界。

限定修法：仅在展示层识别和去除这个现有控制前缀，保留自然语言问题。考虑前缀分片到达时暂缓展示尚未判明的短前缀，避免先闪出 `[QUE` 再消失。不要修改模型原始响应、持久化、后端澄清判定或用户输入续跑机制；最终澄清消息如需清理，也复用同一展示 helper。没有必要新增意图模型或正文分类平台。

补一个完整前缀和一个分片前缀的正式回归；普通正文与 Markdown 不受影响。

## 6. 本次实测与证据

| 范围 | 本次结果 |
| --- | --- |
| 后端正式 `ModelContentPreviewObservationTest` + `AgentContentPreviewPublisherTest` | 10 项通过 |
| 前端正式 `AgentView.streaming.test.ts` + `agent-event-stream.test.ts` + `agent-activity.test.ts` | 47 项通过 |
| 后端 S1 确定性探针 | 1 项失败，实际得到 AI_MODEL_TIMEOUT |
| 前端 S2/S3 组件探针 | 4 项失败；同文件复用的 5 项原测试被 `-t` 筛选排除，不是正式套件的 opt-in 跳过 |

本轮未重跑全量、类型检查、浏览器、真实模型或 PostgreSQL；不把交付报告中的全量数字称作本审查复跑结果。

证据位于 `docs/acceptance-evidence/2026-10-06/real-use-streaming-review/`：

- `ScratchStreamingReviewProbeTest.java.txt`：S1 探针原文。
- `backend-probe-result.txt` / `.xml`：失败断言及调用栈。
- `ScratchStreamingReviewProbe.test.ts.txt`：S2/S3 探针及复用 fixture。
- `frontend-probe-result.json`：Vitest 原始结果。

临时探针执行后已移出正式测试树；其编译产物与本次专用 surefire 报告也清理，避免后续统计混入临时失败测试。未启动任何长驻资源。

## 7. 下一会话的有限任务

以实际最新基线为准，保留本轮四个提交和真实检索成果。只补 S1–S3，按上述失败链组织正式回归，并纠正 51/48 帧口径。不要重新跑旧任务书、真实检索或长对话来替代对这几个边界的验证。

先跑受影响套件；S1 涉及传输与线程边界，补修最终做一次后端/前端全量与类型检查。浏览器只在新增交互无法由组件测试说明时使用，不要求为了此次逻辑补修重复截图验收。没有新证据支持时不扩大功能范围。

验收标准是：慢订阅者不改变模型执行结果，重复/旧事件不回退预览，已结束请求不被迟到帧改写，澄清只显示用户可读文字，同时保留现有流式与最终落库行为。达到这些标准后本轮收口；后续进入正常使用，不继续扩展 provider、框架或计费功能。

## 8. S1–S3 补修收口记录

基线 HEAD `a6eadd6`、分支 `codex/context-foundation`。只补 S1–S3，未重跑真实检索、未改 provider 重试 / 模型 deadline / 运行额度 / 数据库事件序号，未扩展框架、provider 或计费。

### 修法

- **S1**（`AgentEventStreamService`、`AgentContentPreviewPublisher`）：`publishContentDelta` 不再在模型读流线程上同步 `SseEmitter.send`。读流线程只把自包含累计快照写入订阅上的待发槽位（`AtomicReference`，覆盖旧帧安全）并唤醒一个进程内共享、有界（2 worker + 32 队列，`AbortPolicy`）的发送线程池；池饱和时直接舍弃该临时帧。发送线程用同一把订阅锁串行写出，保证同一 SSE 连接不交错；模型读流线程从不获取该锁。发布器再把服务端累计快照限制在展示上限 8000 字符，避免越写越长时持续下发更大的全文快照。
- **S2**（`agent-run-store.ts`、`use-agent-workspace.ts`）：`applyAgentEvent` 返回是否被时间线接受，调用方只在被接受时 `applyPreviewEvent`；旧序号、重复序号或其他运行的事件不再回退预览生命周期。`applyContentFrame` 在请求已定格（`finalized`）后拒绝迟到的新增正文，只接受同一请求的 final 快照，保留已定格文字等待最终消息替换。
- **S3**（新增 `agent-prose.ts`）：展示层 `visibleAgentProse` 去掉开头的 `[QUESTIONS]` 控制前缀，未判明的短前缀（如 `[QUE`）暂缓展示；临时预览与持久化澄清消息复用同一 helper。模型原始响应、持久化、后端澄清判定与续跑机制未变。

### 本轮实测

| 范围 | 结果 |
| --- | --- |
| 新增 `AgentContentPreviewStreamIsolationTest`（4：慢订阅者不把就绪响应变成超时且写入确实发生 / 发布失败不触发 provider 重试且不落库 / 慢订阅者不饿死健康订阅且发布非阻塞 / 临时帧不推进持久游标） | 全过 |
| `AgentContentPreviewPublisherTest` +1（服务端快照限到 8000 且到限后不再增长），共 5 项 | 全过 |
| `ModelContentPreviewObservationTest` | 6 项全过 |
| 前端 `AgentView.streaming.test.ts`（原 5 + 新增 3 预览生命周期 + 3 控制标记） | 11 项全过 |
| 前端全量 `vitest run` | 39 文件 218 项全过 |
| 前端 `vue-tsc -b` | 零错误 |
| 后端全量 `mvnw test`（`ai-collab-backend/target/full-test-s1s3-fix.log`） | 1166 项：0 失败 0 错误，11 显式 opt-in 跳过，BUILD SUCCESS（5m14s） |

边界结论（仅限临时预览通道）：**慢临时预览写不改变模型读取与执行结果**；重复/旧事件不回退预览；已结束请求不被迟到帧改写；澄清只显示用户可读文字；既有流式与最终落库行为未退化。此阶段尚未隔离持久事件发送与 heartbeat 的调用线程（见第 9 节），不能表述为“慢客户端已完全不影响执行”。交付报告的 51/48 帧口径已就地纠正（全运行 51 帧；最终回答轮 48 帧 = 47 增量 + 1 final）。

本轮未做浏览器复验——新增均为逻辑边界，由上述组件与单元回归覆盖；改动尚未提交。

## 9. 补修复核：S2/S3 收口，S1 尚需统一发送

第 8 节是执行会话的交付记录。本次复核实际工作区后，S2/S3 修法及正式回归成立；S1 只隔离了模型读流，持久事件仍会在 Agent 事务提交回调中等待同一连接锁，heartbeat 仍直接占用调度调用线程。两个确定性探针均确认阻塞；本次另复跑后端 15 项、前端 61 项通过，没有重跑全量或真实链路。

因此“慢订阅者不再影响模型读取”成立，“已经完整隔离所有执行侧网络写”尚不成立。只需补齐现有 SSE 发送职责，保留本轮其他成果。完整复核、证据与有限实施范围见 `docs/agent-sse-delivery-review-20261006.md`。用户已澄清末尾“统一发送”的文字是执行模型的建议，本审查会话继续负责判断与设计，不自行修改生产代码或提交。

## 10. S1 完整收口：统一订阅发送入口（2026-10-06）

承接第 9 节，工作区只补完“所有 SSE 网络写退出模型读流线程、事务提交回调与 `@Scheduled` 调度线程”这一件事；S2/S3 保留不重做，未改 provider、框架、运行状态机、重试、暂停恢复、预算或计费。

### 10.1 修法（`AgentEventStreamService`）

每个订阅收敛为一条单飞串行发送路径，网络写只发生在进程内共享、有界的 `agent-sse-send` 线程池上：

- `publish`、`publishContentDelta`、`heartbeat`、`subscribe`（初始 replay）只做非阻塞投递/唤醒：分别置 `durablePending`、写单槽 `pendingContent`、置 `heartbeatPending`，再用 CAS 单飞入队；调用线程不等待网络、发送锁或 Future。
- 持久事件不再由 `publish` 直接携带推送，而由发送端按订阅游标（实际写出的最大 `sequence_no`）批量读取 `agent_run_event`（复用现有 `events.list`）有序追赶。并发 `afterCommit` 的到达顺序不参与排序，因此不会先发较大序号、再因游标跳过已提交的较小序号；读空即追平。游标只随实际写出的持久事件前进，正文帧与 heartbeat 不改游标、不进 replay。
- 初始 replay 也走同一发送线程：先登记订阅再唤醒，登记前已提交的事件由游标追赶读到，登记后的事件由 `publish` 唤醒同一发送端，无空档。
- heartbeat 只登记一次待发意图（布尔标记），不占用调度调用线程。
- 发送池饱和或慢连接只影响该订阅：单飞 CAS 保证同一连接同一时刻只有一个写出者；`AbortPolicy` 拒绝投递时复位待发标记，由下一次事件或 heartbeat 周期重试，绝不 CallerRuns 到业务/调度线程，也不留下永不调度的死订阅。关闭/移除订阅清空待发内容，迟到的临时帧不能复活订阅。
- 临时预览仍只保留最新一帧（自包含累计快照），允许合并/丢弃；持久读取等瞬时失败保留订阅等待重试，不拆连接。

### 10.2 正式回归（`AgentContentPreviewStreamIsolationTest`，10 项）

把第 9 节两个失败探针转成断言式回归，并补顺序/终态/有界/关闭守卫，连原有 4 项共 10 项：

- 阻塞预览写期间，持久发布的调用线程 300ms 内返回，健康订阅者在慢写释放**之前**收到自己的持久事件（原探针 `reviewBlockedPreviewMustNotBlockPersistentEventPublication`）。
- 写入已发生且被阻塞时，`heartbeat()` 调度调用 300ms 内返回（原探针 `reviewHeartbeatMustNotWaitForSubscriberNetworkWrite`）。
- 乱序唤醒（seq 3→1→2）下，持久事件按游标以 1,2,3 **恰好一次**投递。
- 终态事件在更早事件按序送出后才 `complete()`，之后同一连接不再写出。
- 发送池饱和时业务/调度线程不阻塞；池恢复后订阅被后续唤醒补回持久事件。
- 迟到的临时帧与持久事件不能复活已关闭的订阅。
- 原有 4 项保留：慢订阅不把就绪响应变成 `AI_MODEL_TIMEOUT` 且写入确实发生；发布失败不触发 provider 重试且不落库；慢订阅不饿死健康订阅且发布非阻塞；临时帧不推进持久游标。

口径说明：原探针用“未打桩的仓库替身 + `publish` 直接携带事件”观察健康订阅；统一后持久投递以 `agent_event` 为准，正式回归用仓库替身模拟游标语义（`sequence_no > afterSequence` 升序），断言同一性质，不是降低强度。

### 10.3 本轮实测

| 范围 | 结果 |
| --- | --- |
| `AgentContentPreviewStreamIsolationTest` | 10 项全过（连跑 4 次稳定） |
| 受影响后端套件（controller / preview publisher / coordinator / worker / retry / approval / observation） | 全过 |
| 后端全量 `mvnw test`（`ai-collab-backend/target/full-test-unified-sender.log`） | **1172 项：0 失败 0 错误，11 显式 opt-in 跳过，BUILD SUCCESS（7m12s，FULL_EXIT=0）** |
| 前端全量 `vitest run`（本轮未改前端） | 39 文件 218 项全过 |
| 前端 `vue-tsc -b` | 零错误 |

### 10.4 边界结论

慢订阅者不影响模型读取，也不再占用 Agent 事务提交回调与 `@Scheduled` 调度线程；持久事件按游标有序、恰好一次投递，断线可由客户端游标补回；临时预览允许合并/丢弃，不承诺补回。本轮不改前端、不推送、不合并、不部署；改动按第 5 节边界整理为本地提交。
