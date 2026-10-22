# Agent 主链全面审查：委派、提示词、预算与上下文（2026-10-08）

## 1. 基线与证据口径

- 实际分支：`codex/context-foundation`；HEAD：`c911a8518c68ee363855497cd51ab96e2fd9449b`。
- 当前生产改动提交：`813148869e49cd893f5f8d1395b57c6b22e6bf6d`；本地领先 origin 35 个提交。
- 开始审查时仅有既存未跟踪目录 `.freebuff/`、`.dsh-acl-recovery/`，原样保留。
- 本轮不修改生产代码、已有测试、用户配置或业务资料；不 reset、不提交、不 push。新增内容仅为本审查文档与复现证据。
- 已完整阅读研究衔接文档、维护入口至第 13 节、最新拒绝受理交付记录，并核对最新代码 diff。
- 本轮的“已复现”指可控响应/输入驱动的自动化反例，**不是自然触发的真实模型实验，也不是浏览器验收**。
- 本轮没有实现修复，因此只有反例红灯，**不称红-绿验证**。历史模型实验与历史全量测试不能冒充本轮实测。

审查覆盖 Agent 输入、运行协调、模型配置/协议、消息组装、工具策略/执行、委派、预算、持久恢复、暂停/续跑、审批与规划受理、覆盖/引用回收、前端正文解析等关键路径。不是全仓库每一行审计，也不是外部 MCP 服务或提供商的安全审计；SSE 独立审查不重开。

## 2. 发现（按优先级）

### F1 / P1：子运行的澄清响应会进入无法正常回收的等待状态

入口：[AgentRuntimeCoordinator.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:697)。

`completeTextTurn` 对任何 depth 的 `[QUESTIONS]` 开头回答调用 `recordWaitingForInput`。该方法不会把子问题写进主会话，却仍把子运行设置为 `WAITING_FOR_USER_INPUT`，也不触发 `resumeParent`。父运行的 `hasDelegationAwaiting` 把它视为未完成子任务，继续重排队，不综合已有资料。主会话正常输入与 latest-run 恢复入口面向根运行，无法通过正常对话工作流回答这个隐藏的子问题。

这不等于“所有 API 都绝对不能访问子运行”；缺陷是既定只读委派工作流没有可用的子澄清交互/回收契约。父运行会等待，直到取消或其他终止条件生效。

提示词还有相反指令：子角色说“不提问”，继承的 `ProjectResearchSkill` 与通用提示词末尾又要求缺信息时使用 `[QUESTIONS]`、等待用户。这会增加触发概率，不能只责怪模型。

**本轮反例：** `reviewChildQuestionsMustNotStrandParent`，生产协调器/执行器/仓库 + 独立 PostgreSQL，脚本子响应为 `[QUESTIONS]...`。实际：子 `WAITING_FOR_USER_INPUT`、父 `QUEUED`、回收记录 0、父等待轮未再请求模型。

**建议边界：** 子研究缺资料时回传研究缺口，不进入主用户等待状态；同时移除子提示词中普通对话澄清指令。不要增加子对话平台或“继续”按钮。

### F2 / P1：预算降级重组丢失已经回收的子研究文字与覆盖事实

入口：[AgentRuntimeCoordinator.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:290)。

第一次 `composeV2(..., 1.0)` 后追加 `childEvidence`；超预算时重新 `composeV2(..., 0.6)`，却没有重新追加。只要第二版请求放得下，模型就可能在看不到子产出与覆盖块的情况下回答并记为 `SUCCEEDED`。持久化引用仍可在收尾时附上，不能证明模型回答时看过这些证据。

`composer-v2=false` 路径在 `needsFinalRequest` 强制改为总结时重新 `buildMessageHistory`，也没有补回子证据（同文件 320 行附近）。后者为源码确认，未单独执行该分支反例。

**本轮反例：** `reviewRecompositionMustRetainChildEvidence`。生产父子状态推进与持久化，模型响应受控；composer 的两次输出大小受控以确定性触发 0.6 重组。父子均 `SUCCEEDED`、重组次数 1；最终模型请求没有 `CHILD_EVIDENCE_SENTINEL`，也没有 `CHILD_RESEARCH_COVERAGE`。此用例不是实际长对话/真实模型的自然超预算实验。

**建议边界：** 子交付属于本次综合的必要证据层，参与统一容量选择；任何重组/强制总结都保留其最小有效投影，放不下时明确降级交付，不能静默丢掉。

### F8 / P1：输出超额分支丢弃已经返回的回答，子回收再次只剩错误码

入口：[AgentRunEventRecorder.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRunEventRecorder.java:291)。

协调器发现响应输出大于运行剩余额度时，调用 `recordBudgetExceededWithSettlement`，没有进入正常的模型响应持久化。`recordBudgetExceeded(run, completion)` 保存 ERROR 与用量，但不保存 `completion.content()`；随后 `resumeParent(..., "AGENT_BUDGET_EXCEEDED")` 只回传错误码。根运行也没有这份回答正文，子运行同样丢失这一轮研究文字。

运行预算与提供商单次输出上限并非同一个值。当前 Agent 出站沿用配置中的 maxOutputTokens，协调器没有按本运行剩余输出收紧该请求上限；合法配置可以大于子输出上限，或者大于后续轮次的剩余额度。该风险不是计费精度问题，而是“已经发生的调用/返回结果”与“后续动作准入”没有正确分离。

**本轮反例：** `reviewOutputOvershootMustPreserveReturnedChildText`。Native 配置单次输出上限 16384，实际子运行上限 8000；脚本返回无工具正文并报告输出用量 8001。子状态正确为 `BUDGET_EXCEEDED`，但正文哨兵在子步骤里出现 0 次，`DELEGATION_COMPLETED.content` 只有错误码。此为受控提供商用量/响应反例，未用真实模型自然生成 8001 token。

**建议边界：** 不把超额改成成功，不执行该响应提出的新工具；已返回的无工具正文应有界持久化为部分产出，并保留超限状态、真实用量、覆盖与来源限制。支持的提供商可用请求级剩余输出上限预防，不能改用户持久模型配置或破坏单请求快照。不能只继续提高预算来绕开这条丢失路径。

### F3 / P2：覆盖提取把片段/分页历史解释成不准确的章节完成事实

入口：[DelegatedResearchCoverage.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/DelegatedResearchCoverage.java:119)。

四个独立反例均复现：

| 输入事实 | 实际覆盖投影 | 问题 |
| --- | --- | --- |
| 同一标题有两个 body chunk | 正文“已读 2 节” | `items.size()` 是片段数，不是章节数 |
| 第一页 hasMore=true，随后按 continuation 读到结尾 | 仍声明“后续内容未读完” | `readTruncated` 只置 true、不按已闭合续读更新 |
| 从章节中间 chunk/offset 读后缀，最后 hasMore=false | gaps 为空、该标题列为已读 | 忽略 chunk 范围与 fromOffset，未读取的前缀消失 |
| 同一提纲读取两次 | sectionsListed 翻倍 | 累加调用返回条数而非去重后的提纲事实 |

实际正文工具在 [DocumentContentService.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/DocumentContentService.java:63) 返回 chunk、offset、continuation；提纲返回分组标题的起止 chunk。反例使用这些字段形状，不是把章节假定成单个 chunk。

这些投影被标为“已校验事实”，提示词又要求冲突时以它为准，所以错误会被父综合和预算兜底共同传播；增加模型预算并不能纠正它。

**建议边界：** 区分“读取过片段/涉及标题”与“完整覆盖章节”；只在范围证据充分时说读完。可在现有纯函数中去重/合并已读范围；无法证明时保守标未知，不建覆盖平台或额外统计工具。

### F4 / P2：同一文档不同快照被合并，正文覆盖挂到错误版本

入口：[DelegatedResearchCoverage.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/DelegatedResearchCoverage.java:377)。

状态按 `documentId` 单独建键，`snapshotId` 只取第一次非空值。先取得旧快照提纲、后读取新快照正文时，两者的标题/覆盖被合在一起，版本仍显示旧快照。工具允许不传 snapshotId，研究期间文档重新处理后出现这种跨版本结果在契约上是可能的。

**本轮反例：** `reviewDifferentSnapshotsMustNotMergeIntoVerifiedCoverage`；实际投影只保留 `snapshot-old`，却把 `snapshot-new` 的正文计入其覆盖。反例为纯函数工具结果输入，未进行并发重处理文档的真实实验。

**建议边界：** 以文档 + 快照区分覆盖，或明确报告版本冲突/未知，不拼成一个“已校验完整版本”。对未知 snapshot 保持兼容，不推断同版本。

### F5 / P2：子任务上下文隔离在提案层与旧组装路径不完整

入口：[AgentModelMessageComposer.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentModelMessageComposer.java:250)。

- 默认 V2 路径隔离了主会话工作状态/对话历史，但无条件注入 `ctx.proposals()` 及“修订/更新提案、歧义时问用户”的指令。真实 `AgentContextAssembler` 对子运行也加载共享 session 的提案；已有会话提案会进入只读研究子上下文。
- `composer-v2=false` 的 `buildMessageHistory` 没有子隔离：工作状态、摘要、最近主会话消息和项目记忆都会进入子请求，和“只有委派目标及自身观察”的契约矛盾。

**本轮反例：** V2 提供受控可信提案上下文后，子请求包含 `PARENT_PROPOSAL_SENTINEL`/`TRUSTED_PROPOSALS`；旧组装路径读取实际 PG 主会话后，子请求包含 `MAIN_CONVERSATION_SENTINEL`。V2 用例没有在库内创建真实审批提案；assembler 的无 depth 过滤由源码确认。

这不是已证明的跨项目权限泄露：子任务仍使用相同请求人/项目，工具执行权限未放宽。影响是目标污染、额外 token 消耗和错误澄清/写动作尝试。

**注意：** `composer-v2=false` 是消息组装开关，**不是**模型 Native/Legacy 协议；两者不能混为一谈。

**建议边界：** 两条组装路径共用角色所需上下文选择规则，子任务只保留其 objective、允许的观察和必要身份，不靠再追加一段“忽略上文”提示词修复。

### F6 / P2：委派可见性在确定性步数边界上仍暴露必然被拒的工具

入口：[AgentRuntimeCoordinator.java](E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java:542)。

可见性判定使用模型请求前的 stepsUsed；受理判定发生在模型轮次落库、推进步数后。剩余 5 步的 SEPARATED 运行，前者算子预算 3 步而暴露委派；模型轮次耗 1 步后，只剩 4 步，子最小研究 + 父收尾已容不下，受理必拒。

**本轮反例：** `reviewVisibilityMustReserveTheUpcomingModelStep`；实际暴露 delegate，结果为 `AGENT_DELEGATION_BUDGET_INSUFFICIENT`、`REJECTED`、无子运行，父仍 `QUEUED`。软拒绝正确保住运行，但白白消耗一次模型轮次/拒绝调用，可能挤压收尾。

同处 Facts 的 combinedStepsCost 固定为 false，而持久化受理读取真实 budget_semantics；旧 COMBINED 运行的可见性还会高估可用步数。此 COMBINED 分支为源码确认，未单独做 PG 反例。

**建议边界：** 判定新请求可能采取的动作时计入已知的本轮推进成本，并保持真实预算语义；不要求预测未知模型 token 用量，也不新增预算体系。

### F7 / P2：前端把正文或代码里的字面控制标记当成追问协议

入口：[agent-prose.ts](E:/project/ai-collab/ai-collab-frontend/src/modules/agent/agent-prose.ts:41)。

`parseAgentProse` 用任意位置 `indexOf('[QUESTIONS]')`，没有控制行或 Markdown 代码区域判定。助手在正文解释该标记，或者在代码围栏展示示例，会被截成正文 + “需要你的确认”；其后全部内容还会做字面 `\\n` 还原，Windows 路径也可能被破坏。文件中虽定义 MARKER_LINE，但解析函数没有使用它。

**本轮两个纯解析反例：** 引号/行内代码里的标记、代码围栏里的标记都被识别为追问。前者把 `C:\new\notes.txt` 的 `\n` 改为真实换行，后者拆坏代码围栏。

本轮只验证了生产解析函数，未做浏览器视觉验收；`AgentView.vue` 最终正文与临时预览都调用它，受影响入口由源码确认。不能把目前通过的旧路径保留样本当成这些新反例已验收。

**建议边界：** 限定真正的协议控制行，保护 fenced/inline code 和普通引用；继续支持历史中间标记样本，保留正文与追问分段，不全局反转义。

## 3. 子 Agent 到底由谁触发

当前是“模型提出 + 后端控制”的 subagent-as-tool 结构，不是仅在提示词里虚构一个专家：

```text
用户输入 -> AgentRunService.submit -> 持久化根运行
  -> Worker / RuntimeCoordinator
  -> Skill + 当前请求配置快照 + Context/Prompt + 可见工具列表
  -> 模型：直接回答 / 直接用工具 / 请求 delegate_document_research
  -> ToolCallExecutor：身份、权限、schema、批次、暂停/取消、调用额度复核
  -> RunEventRecorder：事务受理、幂等调用身份、切分预算、创建 depth=1 子运行
  -> 同一 Worker/Coordinator 引擎推进子运行，下一请求读取当前模型配置
  -> 子终态回收：文字、引用、coverage、用量 -> DELEGATION_COMPLETED
  -> 父综合 / 有界兜底 -> 主会话最终回答
```

- **模型决定提出什么动作。** 当前没有关键词触发器强制创建研究子运行，用户明确要求“委派”也主要是给模型的指令，不是后端强制路由契约。
- **后端决定是否能看见、能执行、能受理。** depth/角色/定时/MCP 策略、暂停、批次、schema、预算、次数、幂等状态都不是提示词说了算。
- **Skill 存在固定关键词路由。** 这是场景提示词/能力边界选择，不是固定回答，也不等于强制委派。不能说项目“一点硬编码都没有”；权限、协议与业务版本规则本来就需要确定性代码。
- 普通工具调用不必产生第二次模型请求：查询通常是 Java 业务服务/数据库/检索执行；子 Agent 工具则会启动另一个持久运行及其模型轮次。`answer_project_question_with_sources` 当前也是检索别名，不是另一个回答模型。
- 父子不是天然“两个不同模型”：复用现有引擎与配置解析，每次请求按当前设置读配置；请求内使用同一快照，后续请求才切换。
- 父运行拥有直接检索/目录/正文工具，与委派是可替代路径。默认直接研究很正常，**子 Agent 调用率不是质量指标**。

本地课程 `2.3_multi_agent.ipynb` 同样把普通 agent 包装为 tool，由主模型选择；该示例主模型只给两种子工具，项目主运行则还拥有直接研究工具。`3.4_dynamic_tools.ipynb` 的动态工具清单与身份上下文思路也已在本项目采用，无需迁移框架或增加第二套编排。

最新交付文档把一次普通提问未委派表述为“对照实验证明委派只显式触发”，证据强度过高：代码没有“必须包含委派字样”的条件，单个样本只能说明那一次模型选择了直接研究。

## 4. 预算是不是限制太死

**部分默认容量与组合策略偏保守；但本轮发现不能全部归因于预算。**

| 约束 | 当前实现 | 性质与取舍 |
| --- | --- | --- |
| 根运行推进/工具 | 常规 createRun 落库 12 步、8 工具；显式规划 24/16；再与运行时 skill 限制取有效上限 | 应用策略，不是模型天生只能调用 8 次；可评估放宽 |
| 模型轮次与时长 | AgentRuntimeLimits 中按 skill 设置；活跃时间计量、请求 deadline | 防止死循环与过长执行；限额必须保留，但值应按任务测量 |
| 子任务切分 | 子推进最多 8、工具最多 8；委派占 1 次工具；父预留 2 推进步；子输入/输出取父剩余一半并封顶 30000/8000 | 共享总预算会压缩研究容量；不是免费额外 8 次工具 |
| 单次输入 | min(配置模型窗口减预留/余量、运行剩余输入、应用单次 cap)；窗口无覆盖配置时标未知 | 真实模型窗口是硬约束，应用 cap 与预留是策略；不知道窗口不能宣称精确适配 |
| 累计输入 | 每次重新发送上下文都入账；连续研究会反复支付历史成本 | 和“窗口还能放下”不同；大窗口不代表累计预算充足 |
| 总结保留 | 收敛策略按剩余推进/工具与前轮输入成本提前停止取证、转无工具总结 | 合理软收尾，但可能提前到只读提纲；应以资料目标与实测调整 |
| 结果投影/选择 | 老观察缩减，近工具结果保留更大空间；未覆盖旧对话可做有界摘要 | 应淘汰冗余，而非当前目标、必要证据或可信覆盖事实 |

常规根运行总共 8 次工具：扣掉委派自身，父子合计最多剩 7 次；如果父先用了 1 次目录查询，子至多剩 6 次。四文档目录/提纲/正文对照需要多轮动作，未必能放进这个容量。失败样本说明容量与任务不匹配，不能由此断言“委派没有价值”。

这也不是建议取消共享总额：父子若各自无限追加额度，循环、并发与恢复成本会失控。应把两层分开：

1. **硬边界**：权限、项目归属、版本/审批、取消/暂停、幂等、真实窗口与总执行上限，保留确定性控制。
2. **软研究策略**：资料收集深度、子任务大小、提前总结、默认额度与上下文投影，可按目标放宽；触顶时交付已核实材料与缺口。

尤其不要把“已执行动作的结果回收”和“是否还能启动下一请求”混成一个预算判断。现有持久响应优先恢复的方向正确；本轮 F2 是组装路径丢证据，F8 是响应超额后丢正文，F1 是角色状态错误，F3/F4 是事实提取错误，都不是提高阈值能根治的事。

## 5. 提示词优化的有限范围

重要，但不应通过继续堆叠“必须/禁止”来解决机制缺陷。

- **主角色**：讲清直接工具与委派的取舍。委派适合范围明确、可独立交付的小研究；全库对照优先评估直接研究。回收后先综合，不无理由重委派。
- **子角色**：只保留研究 objective、取证与缺口输出。移除普通对话澄清、提案修订、无关任务列表说明及父交互模板；减少冲突和上下文开销。
- **工具描述**：从泛化的“需要多次检索就委派”改为说明目标规模、独立性、所需 source identity 与完成标准；不要承诺受理或完整覆盖。
- **运行事实**：必要时提供紧凑的剩余研究额度/总结阶段事实，让模型知道本轮可完成的范围；事实由后端产生，不由模型改预算。
- **质量验收**：普通短查询、单文档窄研究、多文档对照、资料不足、回收后综合，分别看证据支持、覆盖诚实、用户目标完成与耗时；不要只看是否委派或 status 是否 SUCCEEDED。

这些可在现有 composer、skill、tool definition 和收敛组件内实现；不需要提示词管理平台、自动路由服务、第二套 planner 或新子 Agent 类型。

## 6. 职责与维护检查

| 模块/入口 | 调用与持久边界 | 本轮结论 |
| --- | --- | --- |
| AgentRunService / Repository | 主会话输入、运行创建、控制输入、latest-run、终态重试；agent_session/message/run | 复核了暂停显式输入与根运行恢复；保留现有交互，不加继续按钮 |
| Worker / Coordinator | claim epoch、每 tick 推进、保存响应优先消费、请求准入、收尾与失败分类 | 主链复用方向正确；F1/F2/F6/F8 是分支契约不一致 |
| ModelConfigurationStore / Routing / Native / Legacy | 请求准备解析一次，Native/Legacy 出站；恢复按持久 source_mode 校验 | 配置切换、协议执行回归通过；不把组装开关与模型协议混同 |
| SkillRegistry / PlanService | 固定场景规则、instruction/outputContract、参考步骤 plan_json | Skill 不是可调用 tool；参考计划不是第二套执行状态机；路由固定且需如实说明 |
| ContextAssembler / Composer / Summarizer | 可信身份/页面实体、工作状态/历史/提案、工具观察投影、有界摘要尝试与提交 | 当前目标保护与数据分层保留；子隔离/重组仍有 F2/F5；子提示词过量复用父规则 |
| ToolRegistry / Policy / Executor / Scheduler | 可见性与执行复核；只读并行，规划/审批串行；invocation 结果幂等 | 权限不是模型自觉；最新受理拒绝类型化正确，没有把所有 TOOL_NOT_ALLOWED 软化 |
| DelegationAdmission / RunEventRecorder | depth=1、事务受理、子预算继承语义、同 invocation 回执、结果/用量/引用回收 | 无新引擎，拒绝结果真实落库；F6 可见性事实时点/语义仍需对齐 |
| DelegatedResearchCoverage | 既有工具结果 -> coverage -> 父综合/证据兜底 | 不追加模型统计是正确设计；纯函数不等于事实语义天然正确，见 F3/F4 |
| ApprovalService / approval tools | proposal revision、nonce、操作者权限、目标版本、重校验、事务写入与幂等 | 本轮相关回归通过；未发现需要放宽的权限/版本控制 |
| PlanningOperationService | invocation 绑定、运行行锁受理、goalRevision、attempt/generationSeq、结果版本派生 | 已受理业务任务与对话运行解耦，暂停不应回滚已受理规划；不新增调度器 |
| DocumentContentService / Knowledge tools | 当前正文快照、chunk/offset 分页；语义检索与结构化 citations | 取证工具有版本/范围数据，但 F3/F4 没有正确消费；embedding 可用性属环境前提 |
| MCP provider / tool registry | 项目连接、管理员确认 schema hash、allowlist、只读注解、执行时复核 | 子研究不开放 MCP；本轮仅复核本地边界，未对外部服务做运行审计 |
| AgentView / agent-prose | marked + DOMPurify、最终与临时预览、正文/追问分段 | 渲染管线复用合理；F7 是协议区域识别缺陷，不是 CSS/窗口宽度问题 |

维护风险不是行数：委派角色/标记由 RuntimeCoordinator 定义、ToolRegistry 反向依赖该类，且不同分支重复追加/重建消息，导致“角色规则”和“必要证据”需要在多处同步。应围绕明确依赖与同一组装入口做小范围治理，不为缩短文件进行大重构。该项是维护观察，不是独立必修缺陷。

## 7. 本轮实测清单

### 7.1 既有测试重跑

- 后端 26 个类、**404 项通过，0 失败 / 0 错误 / 0 跳过**。三批：71 + 254 + 79，按当前 XML 核对，不重复累计。
- 前端 Agent 模块 **18 文件、139 项通过**。未重跑全前端 40 文件、未重跑全后端 1239 项，不能沿用历史“全量通过”口径。
- 后端第一批请求列表中误写一个不存在的配置测试名；它没有执行，实际第一批只有四类 71 项。随后第二批执行真实的 AgentModelConfigurationSwitchPostgresIntegrationTest 9 项并通过，计入 254，不虚算首批。
- 后端第二批请求列表中误写不存在的 Repository 测试名；随后第三批执行真实 AgentRepositoryIntegrationTest 40 项并通过，计入 79。
- 没有启动浏览器，没有调用真实模型，也未验证历史待验 Last-Event-ID 或父 RUNNING 并发回收窗口。

详细类名与次数见 `regression-summary.json`；原始运行日志留在 ignored target 下。

### 7.2 审查反例

- Java **11 个反例，11 个期望正确行为的断言失败，0 错误 / 0 跳过**：F1 1、F2 1、F3 4、F4 1、F5 2、F6 1、F8 1。
- 前端生产解析函数 **2 个反例断言失败，0 执行错误**：F7 2。
- Java 探针复用现有委派测试的独立 pgvector:pg17 容器与测试库；模型/上下文输入受控，F2 的 composer 容量受控。未读写生产 PostgreSQL。
- 探针位于 ignored target；执行后清除了本轮编译出的 `AgentFullReviewProbeTest*.class`，不污染后续 Maven 全量测试。没有修改 src/test 中的既有用例。
- 保存源码与断言输出供下一轮转正式回归；这些是失败证据，不是交付通过证据。

证据目录：[agent-full-review](E:/project/ai-collab/docs/acceptance-evidence/2026-10-08/agent-full-review)。

## 8. 建议下一步（限定为三件）

1. **先修委派可靠性与事实保真。** 处理 F1/F2/F8/F3/F4/F5，并把临时反例转成正式回归；F6 在同一新请求准入边界做最小对齐。目标是不会隐藏等待、丢成果或输出错误覆盖事实，不重做预算体系。
2. **再做一次小范围提示词与研究容量校准。** 精简子角色，明确窄任务委派/回收综合，评估常规研究 8 次工具是否满足目标。用固定资料任务比较直接/委派，不把提高子调用率当成果，不先预设必须放宽多少。
3. **单独修 F7 的展示协议识别。** 保留现有桌面工作区，只修普通正文/代码被误分段与反转义；正式修复后用 bsk 做桌面浏览器验收。不是另一轮工作区重设计，也不重开 SSE。

总原则：高质量回答、证据完整与可靠交付优先；正确硬边界保留，研究软策略可调整。自动重试/恢复继续保留；模型下一请求切换；暂停后明确输入续跑；已受理规划继续完成；精确计费不提升优先级。
