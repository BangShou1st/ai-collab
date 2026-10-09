# Agent 上下文容量扩展复核（D1-D8，2026-10-08）

## 1. 结论与范围

- 实际 HEAD `2cf4c51`，分支 `codex/context-foundation`；代码/测试前置 `492fddf`、`03df846`，更早容量基线 `ef2a514`。
- 本轮扩大到上下文的实际出站链：Coordinator、Composer、Summarizer、Native/Legacy 路由、项目记忆、摘要持久化与 claim；不是全项目业务模块或整个 AI 平台的安全审计。
- 没有修改生产代码、用户配置或业务资料，没有提交/push、reset、切换分支或停止现有服务。
- 继承上一条答复的 D1/D2 实测证据，新增六个受控正确性探针。**共八个代码缺口，对应九个探针**，其中 D2 有两个探针。前后两次执行分别统计，不把旧证据冒充本次重跑。
- 本次重新运行四个现有测试类，21 项全部通过。新增六个探针全部失败，说明当前用例没覆盖这些组合边界；不代表那 21 项失败或上一轮全量出现六个新错误。
- 下一轮可在三个开发包中收口，主要修改既有五个模块及直接仓库协作，不需要迁移、框架或新编排平台。

上一条答复的详细复核：[D1/D2](agent-context-capacity-c1c5-followup-review-20261008.md)。
本次新探针与正式测试日志：[扩展证据](acceptance-evidence/2026-10-08/c1c5-expanded-review/README.md)。
下一轮完整开发任务：[衔接提示词](agent-context-capacity-d1d8-handoff-20261008.md)。

## 2. 发现表

源码均位于 `ai-collab-backend/src/main/java/com/shitulelv/aicollab/`。

| ID | 优先级 | 位置 | 已确认问题 | 开发包 |
| --- | --- | --- | --- | --- |
| D1 | P1 | `agent/application/runtime/AgentContextSummarizer.java:817`，`AgentModelMessageComposer.java:332` | partial 首条记录被按完全覆盖整条排除，未送入摘要的尾部也退出主请求 | A |
| D2 | P1 | `agent/infrastructure/repository/AgentRunEventRecorder.java:160`、`:213` | 发布不核对租约有效期，检查与发布没有锁/原子条件；取消可在二者之间先提交 | B |
| D3 | P1 | `agent/application/runtime/AgentContextSummarizer.java:872`、`:946` | `finishReason=LENGTH` 的截断摘要仅因非空、长度较短就提交，并推进覆盖 | A |
| D7 | P1 | `agent/application/runtime/LegacyReadOnlyAgentExecutor.java:143`、`:155` | 实际 Legacy 转换只保留第一个 System/User，丢掉已组装 RUN_CONTEXT 和后续收尾指令等层 | C |
| D8 | P1 | `agent/application/runtime/AgentRuntimeCoordinator.java:385` | 只有摘要“成功”才刷新主请求快照；辅助请求失败后，下一主请求可回到更旧模型 | C |
| D4 | P2 | `agent/application/runtime/AgentModelMessageComposer.java:408` | v2 子研究路径仍调用项目记忆并注入；回退路径的排除条件没有同步到这里 | C |
| D5 | P2 | `agent/application/runtime/AgentRuntimeCoordinator.java:375`，`AgentContextSummarizer.java:243`、`:319` | 会话摘要/重压缩仍与主请求共享单次输入剩余量，v2 近窗口时错误跳过可独立发送的摘要 | C |
| D6 | P2 | `agent/application/runtime/AgentModelMessageComposer.java:341` | 工具层耗满后停止遍历，未访问的旧来源没有进入裁前统计，可能不触发应有压缩 | A |

P1/P2 是本轮审查优先级，不是宣称每条路径已在真实用户会话自然出现。D1/D2 在上一条答复执行了探针；D3-D8 本次执行。

## 3. D3：截断摘要仍推进覆盖

生产路径保存了 `ModelTurnResult.finishReason`，但 RUN_CONTEXT 合格判断只看文字非空和字符数不超过 16000。短且被提供商输出上限截断的结果恰好满足条件，之后将输入源范围标为已覆盖。Composer 再以摘要替换原文，失去依靠未完成摘要恢复完整发现的保障。

本次使用生产 Summarizer/Routing，受控 native 返回非空且短的 `LENGTH` 结果，实际仍以 `COMMITTED` 完成并推进 `sourceThroughSequence=1`：

```text
FAIL lengthTerminatedRunSummaryDoesNotAdvanceCoverage: finishReason=LENGTH advanced sourceThrough=1
```

会话摘要与重压缩的 `qualifies(text)` 同样不读取 finishReason，这是源码确认的同类风险，本次没有分别执行这两个截断用例。修复应统一保护这些辅助产物，但不要以句末标点猜测完整性，也不要给正常回答新增累计输出停机。明确截断的摘要不能直接作为已完成的原文替代品；结算实际用量、保留旧有效摘要与原始来源，沿既有有界降级策略处理。

## 4. D4：子研究上下文的项目记忆入口漏隔离

`composeV2` 对工作状态、会话摘要、近期对话与摘要候选做了子运行排除，但项目记忆只判断 `memories != null`。生产 `AgentRuntimeConfiguration` 确实注入非空 MemoryService，不是仅测试会触达的死代码。

本次使用真实 MemoryService 的相关性选择，底层记忆仓库/权限为受控边界，提供一条 ACTIVE PREFERENCE。PREFERENCE 的相关性分数有固定正值，不需要与委派目标命中；depth=1 实际消息包含标记：

```text
FAIL childV2DoesNotInheritProjectMemory: depth=1 v2 request includes parent project preference; memoryIncluded=true
```

这是违反维护文档第 14.6 节所承诺的子上下文隔离，不是跨项目越权。最小修复为沿用现有子身份判定，在两条组装路径一致排除；正式回归必须提供非空记忆，且保留主运行正常读取记忆的镜像用例。

## 5. D5：不同请求仍错误共享单次输入空间

协调器将 `availableInput - estimatedMain - finalInputReserve` 传给会话摘要，而 `finalInputReserve` 又可等于 `estimatedMain`。主请求约占 H 的一半以上时，传值可为负。Summarizer 在解析自己的容量快照前就因该值拒绝摘要；重压缩也从它继续扣首次消耗。

本次生产 Coordinator + Composer + Summarizer + Routing 链，主请求包含约 90k 字符的历史，仍能放入 50k token 回退窗口；另有未覆盖的短旧消息候选，但摘要从未发出，主运行 SUCCEEDED：

```text
FAIL nearWindowV2ConversationSummaryHasIndependentInput: v2 main request fits 50k fallback, but old conversation summary never sent; status=SUCCEEDED
```

v2 没有累计输入上限，辅助请求和主请求也不是一次 API 请求，它们不应共享同一个窗口余额。改准入来源而非把这个数字放大：每个请求核对自身 H/输出上限/时长；v1 仍保留真实运行累计余量与必要收尾预留，不重解释旧运行。

## 6. D6：工具层提前结束造成裁前统计漏算

工具观察循环把 `toolUsed < toolShare` 写在遍历条件内，层被耗满时直接不访问更旧记录。统计只在循环体中累加，所以这些未入选来源消失于 `droppedSourceChars`，裁前触发仍可能被裁后体积骗过。

本次构造两条未覆盖工具记录：最新一条恰好占满工具层，旧一条约 180k 字符，仍低于既有结果字节保护。实际候选 2 条、入选 1 条，丢弃来源却为 0：

```text
FAIL fullToolLayerStillCountsUnvisitedOlderSources: tool layer filled exactly: candidates=2, included=1, droppedSourceChars=0, omittedOlderChars=180011
```

修复要保证相关、未覆盖、因空间不足退出的来源都纳入裁前估算。不要把明确去重、失效或无关来源重新计为必须压缩的材料；不恢复固定年龄裁切，不改变软触发 T 的意义。

## 7. D7：Legacy 转换破坏已经组装好的必要层

Composer 完整组装不等于 HTTP 实际发送完整。Legacy 的 `extractSystemPrompt` 与 `buildUserPromptWithHistory` 都只取第一个对应角色消息，之后仅追加工具历史。额外 System/User 层，包括 RUN_CONTEXT、会话摘要、当前请求（若不是第一条 User）、子覆盖事实与收尾规则，可能被丢弃。

本次先用生产 Composer 生成带有效 RUN_CONTEXT 的 Legacy 消息，再追加收尾标记，经生产 Legacy 执行器转换，捕获传入受控 ChatModelGateway 的真实命令：两个标记都消失。

```text
FAIL legacyOutboundKeepsCommittedRunSummaryAndGuidance: actual Legacy HTTP command dropped composed mandatory layers: summary=false, finalizingGuidance=false
```

如果刚从 Native 切到 Legacy，已摘要前缀在 Composer 被移走而摘要又在转换时消失，旧证据无法再由这次输入表达。Legacy 不生成新摘要的既有支持限制，不代表可以丢掉已有效保存的摘要。修复只保证消息语义完整转换，保留只读、审批、来源模式及服务器授权边界；不为 Legacy 增加写能力或第二套 Agent 循环。

## 8. D8：失败辅助请求之后重新使用旧主快照

协调器先解析主配置 A，再发送使用自己配置快照的辅助请求；只有 `contextCommitted=true` 才重新解析主配置。辅助请求失败、不合格或无有效提交时，主请求仍使用辅助之前准备的 A。

本次让生产配置解析边界第一次返回 A，之后稳定返回 B；辅助请求实际用 B，受控提供商异常被正常降级；下一次主请求却用 A。每个 HTTP 请求内部快照一致，但实际出站顺序倒退：

```text
FAIL failedAuxiliaryStillRefreshesNextMainRequest: configuration changed A->B, actual outbound order=[model-B, model-A]; failed auxiliary left stale main snapshot
```

下一次实际主请求的准备应在辅助阶段之后完成，不以摘要是否成功为前提。新快照同时用于能力/窗口/输出上限/消息模式/暴露与响应来源校验；在途请求仍用自己的快照，不在 HTTP 层再次重读配置造成另一种漂移。

## 9. 排除的猜测与保留限制

- “摘要后没有重建 exposed 就一定放宽权限”未成立：Registry 的基础集合按角色/Skill/运行身份生成，不依赖模型快照；Routing 的 Legacy 转换过滤业务写工具，执行端还有来源协议/Skill 校验。本轮不为这个猜测重做工具体系。
- 256k/85%/50% 是已批准的候选策略，字符估算不精确、4 个有效 RUN_CONTEXT 周期与 60k 字符辅助输入仍有支持边界，不自动变成新的必修调参任务。
- 模型宣称 1M 窗口不等于当前请求已确认 1M；当前入口靠窗口覆盖配置解析，未知继续标记 estimated 并回退。真实验收需记录实际配置与事件，不凭型号名称假定容量，不擅自改用户配置。
- 已存交付报告的真实模型运行未达到压缩线，因此多周期真实摘要质量仍未观察。这不是本次已做过的验收。
- embedding 环境与旧服务端口问题沿用交付报告描述，本次没有重测，也没有把它们列为新代码缺陷。

## 10. 本次实际验证

1. 六个新增探针：使用当前 HEAD 的直接相关生产源码重新编译到独立 target 输出目录；Repository/native/ChatGateway 等外部边界受控；不反射私有方法、不建立同名生产替身。**6 个断言失败**，无真实模型请求。
2. 四个现有正式类重跑：Compaction 4、Commit PostgreSQL 6、Auxiliary 3、Legacy 8，合计 **21 项，0 失败、0 错误、0 跳过，BUILD SUCCESS**。
3. 正式 PostgreSQL 类使用隔离 Testcontainers、真实 Flyway；未访问业务库。上一条答复 D2 的两个并发/租约探针日志仍是独立的先前执行。
4. 没有重跑 1299 项全量、前端或浏览器；没有修复生产代码，故不是“红绿修复完成”。

## 11. 下一轮三个开发包

| 包 | 内容 | 结束标准 |
| --- | --- | --- |
| A | D1/D3/D6：覆盖、有效摘要、裁前统计 | 实际消息不丢未覆盖尾部；明确截断不推进覆盖；未入选活跃来源计量完整 |
| B | D2：有效租约与取消/目标修订/接管的原子发布 | 真实事务并发用例证明合法提交顺序；旧返回不发布但用量幂等结算；暂停契约不变 |
| C | D4/D5/D7/D8：子数据选择、请求容量、Legacy 转换、下次配置 | 检查实际出站命令而非仅 Composer 中间值；v1/Native/主运行正常行为有镜像回归 |

修复后进行有界真实资料验收：桌面端基本问答、委派、恢复，以及能实际触发两个压缩周期的资料质量抽查。可以使用隔离资料和临时受控运行配置，不改用户生产配置。次数/时间/费用有边界；没有触发就如实保留未验证项，不能把未压缩的 SUCCEEDED 算质量通过。
