# Agent 容量 v2 交付补充审查（2026-10-08）

## 1. 基线与验证范围

- 本轮只读复核生产代码，基线为 `ef2a514`，前置提交为 `9c823bb`、`de9f70d`，分支 `codex/context-foundation`。
- 开始时工作区仅既有 `.freebuff/`、`.dsh-acl-recovery/` 未跟踪目录；未 reset、未 push，未修改生产配置/数据库/源码，未操作用户运行中的后端或前端。
- 已核实取消累计 token 准入、父子独立执行计数及 45/30 分钟策略的主要源码落点。以下不是全面安全认证，也不否定上一轮短资料回答的验收价值。
- 新运行了 3 个生产类探针：真实 Composer / Summarizer，Repository 和模型为 Mockito mock，不依赖反射私有方法、不调用真实模型、不启动 PostgreSQL。最终 3 个预期正确行为断言全部失败，执行无运行异常。
- 探针独立编译到 `target/review-capacity-20261008/classes`，不写 `target/test-classes`，不启动 Maven，不污染全量测试发现。
- 未重跑全量 1286 项、前端或浏览器；上一轮报告的结果不能作为本轮复测结果。

## 2. 发现

### C1 [P1] RUN_CONTEXT 没有成为后续模型上下文

落点：

- [Coordinator 摘要后处理](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:380)
- [Composer 必选层](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:259)

摘要后只刷新 `run`，再用摘要前同一份 `composition` 调用 `assembleRequestMessages`。该方法仅复制已有 messages、追加子证据和尾部指令，不重新读取工作状态、研究摘要或来源。

Composer 两条路径均不读取 `latestRunContextSummary`，也不据 `sourceThroughSequence` 替换已压缩轨迹。全局调用关系显示研究摘要只被 Summarizer 自己读取。因此不仅当前主请求没换成新摘要，后续请求和重启恢复也不会把 RUN_CONTEXT 内容带给主模型。

生产类探针设置已提交摘要独有事实，实际 Composer 请求未包含该事实，且 `latestRunContextSummary` 调用次数为 0。压缩目前有生成/落库行为，但没有完成生成→消费→减小活跃视图的闭环。不能将此仅称为“真实多周期质量待观察”。

修复边界：同一 Composer 读取有效摘要与未覆盖轨迹，保留近期原文和协议闭合；成功后刷新状态/来源并重新 compose，主请求重新解析当前配置快照。不是再加一个摘要引擎。

### C2 [P1] 没有上下文压力时仍固定裁掉旧正文，压缩触发使用裁后体积

落点：

- [固定结果上限](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:53)
- [实际投影](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:317)
- [裁后压缩触发](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java:609)

旧 6000/1500/24000 限制被放大为最新 48000、旧 12000、观察层总计 120000 **字符**，并没有改成按实际窗口压力保留。旧结果仍无条件经 Projector，工具观察总量仍固定封顶。

探针提供 950000 token 可用输入、两条工具结果。较旧结果约 24000 字符，其尾部必要事实仍消失；实际 composition 只有 3647 字符，并记录 1 次投影。材料远未接近真实窗口或 256k 触发线，却已经被裁。

`shouldCompactRunContext` 又从裁后的 `composition.stats().charsUsed()/3` 判断触发，不包括后来追加的子证据/尾部指令/工具定义。因此普通长文研究可在摘要触发前一直丢弃旧原文；所谓“工具定义已计入组装字符”注释与实际 Composer 输入不符。

修复边界：选择必要来源并按完整候选/最终请求估算压力，有空间时不因结果变旧固定裁切；真正压力时再做有据摘要或显式局部投影。有限分页/字节保护继续有效，不把所有资料无条件加载。

### C3 [P1] 截断来源只凭记录头出现就标记整条已覆盖

落点：[coveredPrefixFor](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java:781)

正文按 60000 字符预算截断后，`coveredPrefixFor` 只检测截断文本中是否包含 step ID。ID 在记录头，不代表整个记录内容已送入模型。

探针使用一条约 80000 字符的工具结果：尾部事实没有进入摘要请求，但成功摘要仍记录 `sourceThroughSequence=1`、`sourceTruncated=true`。下一周期按 through 序号跳过整条源记录，未送入的尾部不再参与摘要。一个布尔 `sourceTruncated` 无法恢复遗漏范围。

修复边界：按结构化记录的起止范围确认完整前缀，或保存明确 partial 偏移；超大的首条记录不能因头部已出现而标为全覆盖。Native 调用与同批结果仍须闭合，不采用 UUID 字符串搜索作为覆盖判据。

### C4 [P1] 摘要提交没有目标修订与 claim fencing，旧 worker 仍可发布有效摘要

落点：[completeRunContextAttempt](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRunEventRecorder.java:122)

准入时有 `AgentLeaseScope.verify`，但提交只结算 attempt，并按 step ID 合并 `committedSummary`，没有复核 claim epoch、取消状态或目标修订。`goalRevision` 仅写为 JSON 数据，没有 CAS 比较。

另外 `completeSummaryCallStep` 在 attempt 已完成时虽会返回，外层 `completeRunContextAttempt` 仍继续合并正文；重复完成可能改写已经提交的摘要，而不重新记账。

这是静态源码发现，尚未用真实 PostgreSQL 并发/取消重现。当前“失去租约的旧 worker 不能写入”不覆盖这个新增写入口；上一轮旧会话摘要的 CAS 测试不证明 RUN_CONTEXT 的安全性。

修复边界：实际消耗结算与有效摘要发布分开；失去 claim、目标更正冲突的返回可保留身份/用量，但不能发布为当前有效压缩视图。同一次 attempt 重复完成既不能重复结算，也不能改写已发布摘要；按已受理暂停契约处理，不借此自动续跑。

### C5 [P2] 长请求续租只包主请求，没有包摘要及重压缩

落点：

- [唯一续租调用点](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:451)
- [RUN_CONTEXT 出站](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextSummarizer.java:725)
- [辅助请求路由](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/RoutingAgentModelExecutor.java:156)

主请求周围有 `AgentLeaseRenewer`，摘要和重压缩直接走 `callModelWithoutTools`，没有相同续租范围。HTTP 默认已经放宽到 10 分钟，而 claim 仍为 6 分钟；辅助请求也可能跨过租约边界。

辅助请求会重新解析当前模型，但摘要准入/窗口判断沿用主请求的 budget；没有自己的 `AiRequestOutputCap`。因此也不能用主请求的窗口快照证明这个新出站请求已安全适配。

这是静态调用链发现，未真实等待 6/10 分钟。证据兜底 `completeFromEvidence` 不发模型请求，不属于这里的缺失续租路径。

修复边界：复用统一的出站准入/配置快照/实际输出封顶/有界续租，覆盖所有实际辅助调用。不新增第二套编排，不把短租约统一拉长到 45 分钟。

## 3. 探针证据

归档目录：[agent-capacity-review](E:/project/ai-collab/docs/acceptance-evidence/2026-10-08/agent-capacity-review/README.md)。

```text
FAIL committedSummaryReachesComposer: committed RUN_CONTEXT text absent; repository read count=0
FAIL partialSourceDoesNotAdvanceWholeStep: unsent tail nevertheless covered: sourceThroughSequence=1, sourceTruncated=true
FAIL largeWindowPreservesOlderRawEvidence: older source projected before pressure: availableInput=950000, charsUsed=3647, projectedOutputs=1
Expected-correctness assertions failed: 3/3
```

这是当前实现的反例，不是修复后的红绿验收。没有真实发送 256k 输入或读取用户资料。

## 4. 测试与报告口径

- 全局检索 `src/test` 未找到 RUN_CONTEXT、`compactRunContext`、`renewLease` 或 `AgentLeaseRenewer` 的直接回归调用。
- 当前 `AgentContextSummarizerTest` 25 项仍为既有会话摘要/重压缩/分段/CAS 测试；文件本批未增加 RUN_CONTEXT 用例。旧会话摘要跨运行覆盖不能作为新运行摘要多周期覆盖证据。
- `AgentContextBudgetTest` 的阈值数学断言有价值，但不能证明 Composer/Coordinator/Recorder 的真实压缩闭环。
- 本轮核实的是现有测试源码、保留的报告和 3 个新探针；未重新执行全量。上一轮全量通过与这次发现不矛盾，缺的是这些新增行为的断言。
- 交付文档关于“摘要后重新组装”“多周期压缩自动化覆盖”“短周期续租已验证”等表述，需要补对应真实测试证据或降级表述，不能继续只写成质量观察项。

## 5. 下一轮范围

只收口真实压缩闭环与辅助请求安全边界：修 C1--C5，并把上述探针转正式生产组件回归。建议用较小可控模型窗口强制触发两次压缩，核对实际发给模型的 messages、有效覆盖、原文尾部与引用；用隔离 PG/受控调用测试目标更正、失去 claim、重复提交和续租退出。

保留已落地的累计 token 仅统计、父子独立额度、45/30 分钟方向。无需再次调大所有数字、迁移框架、增加新子 Agent、重做前端或重开 SSE。
