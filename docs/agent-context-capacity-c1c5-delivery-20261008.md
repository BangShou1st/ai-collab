# Agent 上下文容量 C1–C5 修复交付报告（2026-10-08 第二轮）

## 0. 状态

- 分支 `codex/context-foundation`，起点 HEAD `ef2a514`（前置 `de9f70d`、`9c823bb`）。
- 依据：[补充审查](agent-context-capacity-review-20261008.md)（C1–C5 发现）、
  [容量设计](agent-context-capacity-design-20261008.md)、[维护入口](agent-runtime-maintenance.md) 第 14 节。
- 红绿证据：[acceptance-evidence/2026-10-08/agent-capacity-review](acceptance-evidence/2026-10-08/agent-capacity-review/README.md)。
- 本轮只修 C1–C5 与其直接配套；不迁移框架、不建第二套摘要/编排平台、不重做前端、不重开 SSE。
- 已确定方向不变：累计输入/输出仅统计；父子执行额度独立；根 45 分钟/子 30 分钟、
  模型单请求 10 分钟、连接 15 秒；旧 v1 运行按旧语义恢复。

## 1. 修复内容

### C1 RUN_CONTEXT 生成→消费→缩小活跃视图闭环

- `AgentModelMessageComposer.composeV2` 新增**必选层 2c**：读取
  `repository.latestRunContextSummary`，以 `<RUN_CONTEXT_SUMMARY sourceFromSequence=… sourceThroughSequence=…>`
  渲染进实际请求（含"摘要不是当前事实/权限、未覆盖尾部以下文工具结果为准"的边界声明）。
- 工具观察层跳过 `sequence <= sourceThroughSequence` 的已覆盖记录——活跃视图真正缩小；
  近期原文、当前目标、有效用户更正、子研究证据、引用身份各层不受摘要影响。
- `AgentRuntimeCoordinator` 7b：`maybeSummarize` 返回是否提交了新摘要；提交后
  **刷新 steps 与子证据 → 重新解析当前模型配置 → 重算单次请求预算 → 重新 composeV2**，
  不再复用摘要前组好的旧 messages（旧代码仅重发旧 composition）。
- 正常、降级重组（0.6）、强制收尾路径共用同一组装入口，全部消费有效摘要与未覆盖轨迹。

### C2 压力感知投影 + 裁前触发估算

- 移除"变旧就裁"的固定投影：不再按 newest/older 差异化 cap（6000/1500、48000/12000、
  观察层 120000 固定顶全部退出正常路径）。工具观察层上限随本次请求真实剩余预算伸缩
  （约 45%，下限 6000 字符）；只有单条结果放不进剩余空间时才做确定性投影。
- `CompositionStats` 新增 `droppedSourceChars`：按原始体积记录被投影丢弃的尾部与
  因空间不足未入选的工具结果/历史消息。
- 压缩触发改用**裁剪前**估算：`estimateInput(messages, exposed)（含工具定义、子证据、
  尾部指令）+ droppedSourceChars/3`，不再从裁后 `charsUsed/3` 判断。
  有界分页、字节保护、无关内容过滤、去重全部保留；256k 仍是软触发线，未知窗口仍按 50k 兼容回退。

### C3 结构化覆盖范围与 partial 偏移

- 废除 `coveredPrefixFor` 的 step ID 字符串搜索。改为按**记录块结构**逐条累计：
  完整送入请求的记录才计入覆盖；预算截断时记录 `sourcePartialSequence` +
  `sourcePartialChars`（该记录渲染块已送入的字符数），未送入的尾部留在未覆盖来源中，
  下一周期从偏移继续。
- 超大首条记录：送入其前缀并记 partial 偏移，提交推进但**不虚报完整覆盖**
  （`sourceTruncated=true` + partial 字段；布尔 `sourceTruncated` 不再是唯一截断事实）。
- `compressibleSourceSteps` 按 `sourceThroughSequence` + `sourcePartialSequence` 续读，
  已覆盖前缀不重复加载，partial 记录可继续处理。
- 协议闭合不变：覆盖以整条记录为边界，Composer 的工具消息按 `TOOL_CALL_COMPLETED`
  整条重建（Assistant toolCall + ToolResult 成对），不留孤立 result。

### C4 RUN_CONTEXT 提交 fencing 与幂等发布

- `completeRunContextAttempt` 重写（Recorder）：**发布与结算是 agent_step 行上同一次
  ATTEMPTED → 终态转换**（`output_json = 终态记账 || committedSummary`，单条 UPDATE）。
  重复完成转换零行——不重复结算、不改写已发布摘要。
- 发布前在同一短事务内核对（fencing）：
  - **claim epoch**：当前线程持有时经 `AgentLeaseScope` 比对 `claim_version`，
    失去租约的旧 worker 不得发布；
  - **取消**：`cancel_requested_at` 已落库的返回不得成为当前有效摘要；
  - **目标修订**：与会话工作状态 `goalRevision`（真实业务修订事实，新增
    `AgentRepository.currentGoalRevision`）比对，生成期间目标更正的返回不得发布；
    不再把任意 `run.version` 当成目标修订。
- 被 fenced 的返回以 `FENCED`（note 记原因）落账，**已发生用量仍如实幂等结算**。
- 已受理的暂停按契约允许摘要完成保存；不自动解除暂停；短事务，不跨模型 HTTP 持锁。

### C5 辅助模型调用统一出站规则

- `AgentContextSummarizer` 每次实际辅助出站（会话摘要、重压缩、RUN_CONTEXT 压缩）：
  1. 解析**自己的**配置快照（`resolveAuxiliaryCapacity` → `modelExecutor.resolveRequest`）；
  2. 用同一份快照核对模型窗口（`AgentContextBudget.perRequest`，估算超自身 H 时跳过，
     不发无意义请求；重压缩在准入后另核对自己的快照）；
  3. 按本次快照下发 `AiRequestOutputCap`（不再借主请求 budget，也不再无封顶出站）；
  4. 请求期间 `AgentLeaseRenewer.forCurrentClaim` 有界续租：退出、取消、失去租约、
     到限即停止（`renewLease` 返回 false 或异常即停）。
- `RoutingAgentModelExecutor.callModelWithoutTools` 新增快照入参重载，
  准备与出站用同一份配置。
- 证据兜底 `completeFromEvidence` 不发模型请求，未为其增加续租或新模型调用。

## 2. 测试构成与红绿

### 2.1 探针转正式回归（未修复基线红灯实测，修复后转绿）

`AgentRunContextCompactionRegressionTest`（4 项，真实 Composer/Summarizer/Routing 执行器，
仅 Repository 与 native 执行器为受控替身，无反射、无同名替身）：

| 用例 | 修复前基线（ef2a514 worktree 实测） | 修复后 |
| --- | --- | --- |
| committedRunContextSummaryReachesComposerAndReplacesCoveredPrefix | FAIL（摘要缺席） | 通过 |
| unsentTailStaysUncoveredWithPartialOffsetInsteadOfWholeStepCoverage | FAIL（虚报整条覆盖） | 通过 |
| largeWindowKeepsOlderRawEvidenceWithoutPressureProjection | FAIL（无压力裁正文） | 通过 |
| compactionTriggersOnPreClipActiveSources… | FAIL（按裁后体积不触发） | 通过 |

完整失败输出见 [baseline-red-run.log](acceptance-evidence/2026-10-08/agent-capacity-review/baseline-red-run.log)
（`Tests run: 4, Failures: 4`）。worktree 内两处编译适配见证据 README，断言语义不变。

### 2.2 C4/C1 隔离 PostgreSQL 回归（新增，真实持久化）

`AgentRunContextCommitPostgresTest`（Testcontainers `pgvector/pgvector:pg17` + 真实 Flyway，6 项）：

- 重复完成：不重复结算（input_tokens_actual 不变）、不改写已发布摘要文本、周期数不涨；
- 取消：被取消的返回不成为当前有效摘要（latestRunContextSummary 为 null、周期数 0），
  已发生用量如实结算（1000 input 落账）；
- 失去 claim：旧 worker（epoch=1 vs claim_version=7）不能发布，用量照常结算；
- 目标修订冲突：生成期间 goalRevision 2→3 的返回不发布；同一修订（3）下正常发布，
  摘要携带 goalRevision=3；
- **已提交摘要经真实持久化进入实际 Composer 消息**：摘要文本在场、覆盖前缀原文缺席、
  未覆盖近期原文在场；
- **两个压缩周期经真实持久化推进**：cycle1 覆盖 seq1..2 → cycle2 只压缩新来源 seq10、
  `previousSummaryIncorporated=true`、请求延续上一份摘要文本且不重复送入已覆盖原文。

### 2.3 C5 辅助出站回归（新增）

`AgentAuxiliaryOutboundRegressionTest`（3 项）：

- 摘要出站调用期间 `AiRequestOutputCap.active()` 且封顶值等于本次快照派生值（1024）；
- `AgentLeaseRenewer` 生命周期：健康时按短周期持续续租、`renewLease` 返回 false 即停、
  停止后无泄漏调用、无 claim 上下文为 no-op；
- 每次辅助请求经真实 `RoutingAgentModelExecutor.resolveRequest` 解析自己的快照。

### 2.4 既有回归适配（非语义变更）

- `AgentContextSummarizerTest`：装配补 `resolveRequest` stub（C5 每次出站解析快照）、
  `callModelWithoutTools` 捕获改三参形态；25 项全部通过，断言语义未改。
- `AgentPauseResumePostgresTest`：同上三处 stub 形态适配；语义断言未改。

### 2.5 全量与前端

见第 4 节实测清单（收尾时按真实日志补齐）。

## 3. 边界与未验证项

- 未真实发送 256k 输入、未消耗 8M token、未等待 45/30 分钟——全部经受控窗口/受控模型/
  结构化断言验证；与上轮口径一致。
- 多周期压缩的**真实模型摘要质量**（信息是否丢失）仍需真实资料任务观察；
  本轮自动化证明的是覆盖推进、请求内容与消费闭环。
- 目标修订 fencing 依赖会话工作状态的 `goalRevision`：子运行共享父会话状态，
  用户对父目标更正同样拦截子运行的在途摘要发布；未对"子目标独立修订"建模（现状无此事实）。
- `droppedSourceChars` 是字符级保守估算（与 chars/3 同源），不是 tokenizer 精确计数。
- 摘要请求自身超窗（部分窗口极小的模型）时按跳过处理：保留旧摘要与原上下文，不无界重试。

## 4. 实测清单

### 4.1 后端全量（本轮真实执行）

```
.\mvnw.cmd -o clean test
→ Tests run: 1299, Failures: 0, Errors: 1, Skipped: 11
（唯一 Error 为 Testcontainers 端口映射 BindException 环境抖动，
 单独重跑该类 23/23 通过；另一次全量中曾出现 46 个 Spring 上下文错误，
 根因是本轮新增的双构造器装配歧义，已补 @Autowired 后全部通过——
 过程如实记录，不以首次失败冒充通过或以重跑掩盖首次失败原因。）
```

- 11 个跳过为既有 opt-in 门控，非本轮新增。
- PostgreSQL 回归全部使用 Testcontainers `pgvector/pgvector:pg17` 独立容器，
  不读写生产库。

### 4.2 前端（本轮无前端代码改动）

- `vitest run src/modules/agent` → 18 文件 / **145 项通过**（2026-10-08 实测）。

### 4.3 浏览器验收（bsk，真实模型，受控窗口）

完整记录见
[acceptance-evidence/2026-10-08/c1c5-browser/browser-acceptance.md](acceptance-evidence/2026-10-08/c1c5-browser/browser-acceptance.md)。要点：

- **简单事实查询**：1 次工具调用，total=0 如实回答，无扩容。
- **四文档直接研究**：`来源（15）`，引用带章节定位，`SOURCE_DATA_ONLY` 边界如实标注。
- **委派全链路**：父运行 `d7c2789c`（3 子运行、10 步/8 工具、累计输入 141142）`SUCCEEDED`；
  回答正确给出 500 人上限、0.8% 实测及其"未在 500 并发验证"的文档缺口标注。
- **刷新与跨标签恢复**：问答/执行过程/引用完整恢复，无"继续"按钮。
- **受控压缩触发**：窗口压至 30000（`T≈23375`）下真实运行活跃上下文峰值
  （9058 tokens，`inputBreakdown` 实证）低于触发线，**未触发真实模型压缩周期**；
  压缩闭环由 2.1/2.2 的隔离回归覆盖，不表述为"真实模型已实测压缩"。
- 新增 `MODEL_STARTED.inputBreakdown.runContextChars` 字段已接入真实事件（本轮为 0，
  与无摘要一致）。

## 5. 文档更正

- [上轮交付报告](agent-context-capacity-delivery-20261008.md) 中"提交后协调器重新组装主请求"
  "至少两次研究窗口压缩可推进""长请求配套有界租约续期"等表述在 `ef2a514` 时点仅有
  部分实现/静态依据，实际缺陷见[补充审查](agent-context-capacity-review-20261008.md)；
  本轮修复与实测以本报告为准。
