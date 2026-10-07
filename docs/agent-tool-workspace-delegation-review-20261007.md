# 工具契约、工作区与子 Agent 交付代码审查

> 日期：2026-10-07。审查基线：`codex/context-foundation` / `00a85fe43ffed3b7fe6bdff4a5575b10472859c0` 加当前未提交交付改动。
> 本轮是代码审查与复现，不是修复交付。没有修改生产源码、没有提交或推送、没有调用真实模型。
> 最新用户约束：前端只做电脑端适配，不再要求手机端验收。1100px 案例是桌面窗口，不是手机。

## 结论

工具 Schema 补齐和布局改进的方向可以保留，但子 Agent 交付存在实际隔离、结果恢复、预算和来源缺陷，当前不宜直接视为验收完成。

输出展示也尚未达到用户的阅读体验要求。全尺寸桌面窗口不存在中等窗口的遮挡，但不能解决回答章节、步骤与追问被合并成连续编号的问题；这是输出结构和展示边界共同造成的，不应仅归因于浏览器尺寸。

不能用开发报告中的 525 项通过证明这些边界。此次复跑既有测试仍通过，新增缺失场景的审查探针则明确失败。

## 本轮验证

| 项目 | 本轮实际结果 | 边界 |
|---|---|---|
| 后端既有定向测试 | 48 项通过，0 失败/错误/跳过 | 委派 4、配置切换 9、Schema 验证 26、生产 Tool 契约 9；不是重跑全部 525 项 |
| 前端既有测试 | 40 文件、223 项通过 | 有测试组件 stub 警告；本轮没有重新运行 vue-tsc |
| 临时委派审查探针 | 12 项执行，8 失败、4 通过，0 错误/跳过 | 继承既有委派 4 项；另外 8 个缺失不变量全部复现失败；隔离 Testcontainers PostgreSQL |
| 正文纯函数检查 | 普通 Windows 路径被改写 | Node 直接调用实际 visibleAgentProse，不是模型生成质量测试 |
| 电脑端中等窗口检查 | 1100x800 下对话与关闭入口被遮挡 | browser-skill/bsk；真实页面，未发送新问题、未修改业务资料 |
| 全尺寸桌面窗口补查 | 三栏不重叠，隐藏/恢复详情可操作；回答编号问题仍存在 | 按屏幕可用尺寸 1707x1019 调整窗口，实际 outer 1708x1020、inner 1685x880、DPR 1.5；不是手机模拟，也不声称已进入操作系统最大化/F11 状态 |
| 回答原文与 DOM 对照 | 原文的章节 4、步骤 1-3、追问选项 1-3，最终进入同一个 ol，显示为 4-10 | 从当前页面 Vue 消息状态只读获取原文，与实际渲染 DOM 对照；本轮没有重新请求模型 |

证据位于 `docs/acceptance-evidence/2026-10-07/delivery-code-review/`：临时探针源码、JUnit 结果与浏览器截图。探针不放进正式 src/test，也不留编译 class 参与下一次全量测试。

## 已确认发现

Java 源码前缀为 `ai-collab-backend/src/main/java/com/shitulelv/aicollab/`。

### R1 [P1] 子运行答案进入主会话

- 位置：`agent/infrastructure/repository/AgentRunEventRecorder.java:922`。
- `recordFinal` 不分 depth，始终向同一 session 的 agent_message 写 ASSISTANT。新子运行共享父 session，因而研究中间答案直接成为主会话回答；父综合后再写一份。
- 探针 `childAnswerMustNotBecomeMainSessionMessage` 复用了原委派生命周期测试：期望子 ASSISTANT 消息 0 条，实际 1 条。
- 应只持久化子运行自身最终结果，再由父运行综合；同时审查其他终结/澄清/预算路径的消息与 working_state 写入，不能只挡正常成功一处。

### R2 [P1] 会话恢复把子运行当主运行

- 位置：`agent/infrastructure/repository/AgentRepository.java:89`，以及 session summaries 的同类子查询。
- `findLatestRun` 按 session 的所有运行 created_at 倒序取第一条，没有过滤父子范围。子运行后创建，latest-run 返回它；前端 restoreSession 用此结果恢复状态和 SSE 订阅。
- 探针 `latestSessionRunMustRemainTheRoot` 期望父 ID，实际返回子 ID。刷新或切会话后可能控制子运行，而真正父运行仍等待/执行。
- 应区分用户会话的主运行身份与内部子运行，同步处理摘要、控制输入和重试入口。

### R3 [P1] 子工具窄白名单被基础集合覆盖

- 位置：`agent/infrastructure/tool/AgentToolRegistry.java:106`；执行端的基础集合放行规则同样存在。
- Coordinator 的 restrictedChildSkill 虽只声明五个文档工具，Registry 仍对所有 depth 合并 BASE_READ_ONLY_TOOLS。普通任务、成员、项目记忆和统计工具会对子运行暴露；不是声明中的文档窄范围。
- 探针 `restrictedChildMustNotReceiveCommonRootTools` 使用真实 TaskGetAgentTool 与实际 restrictedChildSkill：allowedTools 没有 get_task，但最终 definitionsFor 仍有它。
- 基础只读扩展应限主运行，子运行按自己的有效窄范围暴露和复核。未发现由此获得业务写权限或跨项目权限，不能把范围缺陷扩大说成已发生越权写入。

### R4 [P1] 混合批次委派拒绝后仍伪成功

- 位置：`agent/application/runtime/AgentToolCallExecutor.java:185` 至后续只读批次执行。
- 委派与其他合法工具同批时先记录 DELEGATION_BATCH_LIMIT，但委派对象已加入 readOnlyBatch，并未剔除；随后调用 DocumentResearchDelegateAgentTool.execute，该方法只返回 DELEGATED 回执，不创建子运行。
- 探针 `rejectedMixedBatchMustNotPublishFakeDelegationReceipt` 同批提交委派与合法知识检索：子运行 0 条，委派 invocation 却最终 SUCCEEDED，前一个拒绝被后一次记录覆盖。
- 应给整批调用明确且唯一的处置，拒绝委派后不能进入普通执行；不通过 Tool.execute 制造没有受理事务的成功结果。

### R5 [P1] 父暂停时子用量不回收

- 位置：`agent/infrastructure/repository/AgentRunEventRecorder.java:1247`。
- resumeParent 把用量累计与状态唤醒绑在同一 UPDATE，只接受 CREATED/QUEUED。父处于 PAUSED 时仍落 DELEGATION_COMPLETED，但用量 UPDATE 为 0 行；恢复路径只读发现，没有补记。
- 探针 `pausedParentMustStillCollectChildUsage`：子运行实际输入 fixture 为 1234，完成时父保持 PAUSED，父实际累计却仍为 0。父处于 RUNNING 的竞争窗口也被该条件排除，但该并发窗口本轮未复现。
- 已发生用量的幂等回收应独立于能否唤醒父运行。这是共享预算/执行边界，不要求开展精确计费体系。

### R6 [P1] 电脑端中等窗口完全遮挡对话

- 位置：`ai-collab-frontend/src/modules/agent/AgentView.vue:492`。
- 761-1280px 时，网格仍有会话与对话两项，却改为 `minmax(0,1fr) 300px`；会话拿到宽列，对话被压成 300px。详情栏 position:fixed、top:0、宽 400px，又覆盖整个对话和顶部隐藏详情按钮。
- 1100x800 实测：会话宽约 455px，对话宽 300px/x=744，详情宽 400px/x=684.7，隐藏按钮 x=957.3，均落在遮挡范围内。截图像素为 1650x1200（DPR 1.5），CSS 窗口仍是 1100x800。
- 用户指出初始检查窗口未铺满后，补查显示器可用尺寸的桌面窗口：workspace 宽 1160px，三列约为 228/614.7/316px，详情 position:static，三栏和关闭入口没有遮挡。此缺陷限定于中等桌面窗口，不能用它概括宽屏默认布局。
- 只需整理电脑端断点/列宽及详情关闭入口，不要求重新开展手机端设计。

### R7 [P2] 子引用身份在父综合后丢失

- 位置：`agent/infrastructure/repository/AgentRunEventRecorder.java:1235` 与 `recordFinal` 的本运行引用查询；`agent/application/runtime/AgentRuntimeCoordinator.java` 的 childResearchEvidence。
- DELEGATION_COMPLETED 只带 status/content，childResearchEvidence 只注入正文；父 recordFinal 只搜父自己的 TOOL_CALL_COMPLETED，不能获得子工具的有效 chunk/document 身份。
- 探针 `parentFinalAnswerMustRetainChildSourceIdentities` 创建真实可校验的正文 chunk 与持久引用 fixture：子最终结果有 1 条有效 citation，父最终结果为 0。无需 embedding 即可验证持久来源投影链。
- 应保留可验证的结构化来源与覆盖信息，再由父结果投影到来源查看；不能信任模型凭正文重新编造 ID。

### R8 [P2] 委派调用不计入工具配额

- 位置：`agent/infrastructure/repository/AgentRunEventRecorder.java:1343`。
- 特殊受理事务只增加 children_used，不像正常 recordToolResult 那样增加 tool_calls_used/steps_used；委派 invocation 已 SUCCEEDED 却不计工具配额。
- 探针 `acceptedDelegationMustConsumeAToolCall` 经 Coordinator 真实分派：子已创建，父 toolCallsUsed 仍为 0，预期为 1。
- 应明确委派自身与子运行消耗的计数，并与子预算切分、父收尾预留一致，避免因为特殊分派出现免费调用或重复计数。

### R9 [P2] 子上下文没有隔离父对话

- 位置：`agent/application/runtime/AgentModelMessageComposer.java:224`、`:302`；旧 composer 同样按 session 读历史。
- 子与父同 session，composeV2 仍读整个会话 working_state、摘要和最近消息。工具说明承诺子只收到自包含 objective、不见用户历史，但没有实际隔离。
- 探针 `childModelMustNotReceiveParentConversationHistory` 插入仅属于父对话的测试标记，实际子模型 messages 包含该标记及父工作目标。
- 为子组装明确的委派目标、受限上下文与自己的工具观察，不把父用户更正/提案/摘要自动继承；不需要新编排器。

### R10 [P2] 正文处理改写普通代码与路径

- 位置：`ai-collab-frontend/src/modules/agent/agent-prose.ts:26`。
- 最后的全局字面 `\\n` 转真实换行对所有回答生效，不限 QUESTIONS 控制块。普通代码、JSON 转义或 Windows `C:\new\notes.txt` 都可能被改写。
- Node 调用实际函数确认该路径输入与输出不相等，路径里的两个 `\n` 被替换为换行。现有普通正文测试不含转义序列，因此通过并不能覆盖此回归。
- 控制标记展示处理应局限已识别协议区域，普通正文和代码保持原样；不要用全面反转义修复某一次模型表现。

### R11 [P2] 章节、步骤与追问的输出层级混淆

- 位置：`agent/domain/model/builtin/ProjectResearchSkill.java:31`；`agent/application/runtime/AgentModelMessageComposer.java:615`；`ai-collab-frontend/src/modules/agent/agent-prose.ts:20` 及 `AgentView.vue:405` 附近正文样式。
- 研究 Skill 的输出约定以 `1. 研究问题摘要` 至 `4. 建议的后续步骤` 表示章节，而不是 Markdown 标题。当前页面原始回答照此组织；建议部分又使用同级 `1./2./3.`，追问以末尾 `[QUESTIONS]` 和字面换行拼接。
- 实际 DOM 中前三章也只是 ol/li，不是 h2/h3；末尾为 `<ol start="4">`，依次包含章节名称、三个步骤和三个追问选项。标记处理没有保留独立追问结构，问题文字落在第三个步骤的段落中，因此显示编号 4-10。不是 CSS 错把真正标题显示成列表，也不是用户显示器尺寸导致。
- 当前正文实际字号 14.5px、行高 25.375px。h2/h3 已有不同样式，但原文没有输出这些语义标签时，现有标题 CSS 无从生效。只改字体或把全部列表项加粗都不能恢复信息层级。
- 下一轮共同整理：共享的轻量回答排版约定、场景模板的标题语义、识别到的追问区域的独立分段，以及前端正文样式。优先结论，长答按需分节，关键事实适量加粗；步骤列表和选项各自从 1 开始。短问短答，不强制每个回答填满报告栏目。
- 不依靠前端猜测中文短句并强行变成标题，不把所有回答改成固定 JSON，也不引入新的编排框架。保留普通代码/路径、流式与最终回答共用安全渲染，以及明确输入续跑的交互。
- 对历史混合追问可做有边界的展示兼容，但本轮仅验证显示事实；不把标记出现在末尾时的暂停语义当成已完成状态机验收。

## 测试覆盖说明

- 原委派生命周期测试的 Registry 只注册 knowledgeSearch、questionAlias、delegateTool，但脚本子响应调用 list_project_documents；该工具未注册，实际走失败回执后脚本继续给结论，不足以证明成功文档研究链。
- 原白名单测试仅断言子看不到委派工具，并没有注册、检查被基础集合扩出的任务/记忆工具。
- 原最终消息断言只检查 ASSISTANT 列表非空，没有断言 child 消息为 0、父回答只有一份。
- 原用量测试不覆盖父暂停/父 RUNNING 竞争窗口，也没有来源跨父子回收断言。
- 上述是测试空白，不否认历史全部通过的事实，也不把脚本模型 fixture 当真实模型质量证据。

## 下一步限定范围

1. 先修 R1-R5 的身份、范围、混合批次与用量边界，同时补 R7-R9 的引用、配额和上下文隔离。沿用当前运行机制，不扩多 Agent 平台。
2. 修 R6/R10/R11 的电脑端布局、正文保持和输出层级；先用本轮失败回答及代码/路径作为固定回归样本，再检查真实模型回答。按用户新要求仅验收电脑端常用分辨率和窗口缩放。SSE 完成态文案可作局部 UX 整理，不重开发送层。
3. 将临时探针转成正式、稳定的定向回归测试，避免只继承测试脚本/反射私有实现。补父暂停/取消、恢复竞争、澄清和引用的真实持久边界覆盖。
4. 修复后再运行受影响回归与电脑端浏览器验收；真实文档研究质量对照需先准备有效项目资料和 embedding 配置。当前页面历史证据显示未配置嵌入模型，不能仅据报告认定只需启动服务。
5. 在用户授权的开发阶段按能编译、测试通过的逻辑交付做本地提交，并给出哈希及对应测试。当前只审查，不把尚有已确认缺陷的工作区宣称为验收完成；无关 .freebuff、.dsh-acl-recovery 和继承 SSE 记录不混入提交。

本轮不新增手机适配、模型迁移、第二套循环、精准费用或 SSE 发送层任务。父暂停之后已受理的业务规划继续完成的既有契约不改。
