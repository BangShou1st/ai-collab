# 能力矩阵

## 2026-10-20 当前基线与 P3 规划入口

当前研究基线 `dev@09d80639185fbb89e74b71d37d3af272bf43ad5f`。最新能力/验证分层、候选需求与 P2 风险以 [P3-SCOPE-01](./archive/research/p3-scope-01.md) 为入口；下文 2026-10-03～05 的 P1–P4 为历史交付编号，不是新 P3 待办。历史静态缺口必须先核对后续实现，不能直接重复开发。

| 当前增量事实 | 验证层级与限制 | 证据 |
| --- | --- | --- |
| RAG、Agent 检索来源、AI 规划8任务编辑确认及刷新已通过 | 真实模型+浏览器；并发/所有异常不外推 | [P2闭环](acceptance-evidence/2026-10-19/p2-business-e2e-02/README.md) |
| Member 私有项目中性404、Ollama停机/恢复已复验 | 真实浏览器/本机服务；历史6份文档未代操作 | [HARDEN](acceptance-evidence/2026-10-19/p2-harden-01/README.md) |
| Agent SSE同页断线→传输重连→事件对齐已通过 | 受控真实Edge/后端/免费模型；历史ERR_ABORTED仍未归因 | [OBS-02](acceptance-evidence/2026-10-20/p3-obs-02/README.md) |
| 无本地证据AbortError归UNKNOWN；重连未对齐再断开退回RECONNECTING | 本轮测试先红后绿；仅诊断模块，不宣称新增浏览器验收 | [SCOPE记录](acceptance-evidence/2026-10-20/p3-scope-01/README.md) |
| 工作台我的待办直达任务详情 U-01 已批准并实现 | 路由/竞态自动化 + 真实 Edge 桌面/窄屏与 MEMBER 权限；无模型调用；后续 U-02/U-03 独立交付见下行，U-04 后续最小增量见下行，U-05 未实施 | [TASK-01 验收](acceptance-evidence/2026-10-20/p3-task-01/README.md) |

| 任务通知直达任务详情 U-02 已批准并实现 | 27 项通知测试、4 项深链衔接/离页回归、3 项真实 PostgreSQL 测试；真实 Edge 桌面/390px/键盘、删除与跨项目目标、Owner 移除 Member；无模型调用。已读失败/网络乱序为自动化证据 | [TASK-02 验收](acceptance-evidence/2026-10-20/p3-task-02/README.md) |

| AI 配置状态准确性 U-03 已批准并实现 | 工作台不再把“存在配置”显示为“已就绪”；个人 AI 设置按用途显示解析状态与“未验证/本次会话测试”；加载失败显示未知而非未配置；无自动模型探测；Member 无系统 Embedding 入口。自动化 + 真实 Edge；未注入真实 Provider 429 | [TASK-03 验收](acceptance-evidence/2026-10-20/p3-task-03/README.md) |

| 资料失败恢复指引 U-04 最小增量已批准并实现 | 已有安全文案精确映射原因与责任角色；未知错误不回显原文；单项重试防重复、提交与最终状态分离、刷新失败未知、只读禁用。自动化 + 真实 Edge/后端解析失败与 Owner/Admin 重试、Member 403、撤权中性 404；Embedding/未知错误仅受控响应，不宣称真实索引恢复 | [TASK-04 验收](acceptance-evidence/2026-10-20/p3-task-04/README.md) |

以下保留原历史矩阵；其中“最后更新”只描述当时快照。

维护约定（依据 [ai-next-stage-blueprint.md](./design/ai-next-stage-blueprint.md) 8.3）：

- 本表记录**当前 checkout** 的能力状态；历史审查（`ai-capability-review.md`）与验收报告只作为证据链接，不混列成当前待办。
- 验证层级：`单测`（Mockito 纯单测）→ `集成`（Testcontainers 真实 PostgreSQL）→ `真实模型`（授权副本上的真实提供商验收）→ `浏览器`（产品界面实际操作）。高层级不自动覆盖低层级未覆盖的分支。
- 状态口径：`已验收`＝有对应证据；`缺口`＝蓝图已确认待处理；`待评测`＝功能存在但质量未用固定用例度量。

最后更新：2026-10-05（Agent 稳定性剩余缺陷集中修复，分支 `codex/context-foundation`）。当前质量结果见顶部专项记录；工具可调用不等于整个业务能力全面验收。

本轮（五组剩余缺陷）结果见 [稳定性剩余缺陷修复报告](acceptance-evidence/2026-10-04/agent-stability-remainder/report.md)。**并纠正上一轮的不准确声明**：上一轮"10→8→6 全链路正确"实际中间态不成立（真实第 10 轮 8/10 被标待澄清、旧 10 仍 active），本轮以真实原句复现修复后，8 active/10 superseded 已在真实运行验证；上一轮"仅剩规划正文未验"本轮已补验（真实草稿零旧数量残留），并在检索可用环境下首次取得规划来源真实证据。摘要重压缩预算受控且按调用记账（真实 8 次重压缩独立 PROVIDER 入账）、取消结算以 V57 账本幂等、预算收尾未做核心动作不再记 SUCCEEDED（真实第 23 轮实际受理规划，失败重试链路保留）。**fixed24 为 22/24 SUCCEEDED：第 9 轮宿主中断恢复、第 15 轮真实输入 53437>50000 超额终止，均为诚实结果**。后端全量 1022 项：1011 通过、11 显式 opt-in 跳过、零失败/错误；前端 143 项、vue-tsc、Vite 构建及后端打包通过。新增 V57 迁移仅应用隔离副本。开发分支推送仅用于备份，不代表正式发布。

## 1. 基础设施与模型接入

| 功能 | 关键组件 | 模型要求 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 多提供商模型路由（OpenAI 兼容 / OpenCode Zen / Ollama） | `infrastructure/ai/model/Routing*`、`ProviderPresetRegistry` | 按用户与用途路由 | 已验收 | 集成 + 真实模型 | [space-bunny-acceptance-report](./archive/reports/space-bunny-acceptance-report.md) |
| 模型配置快照与凭据加密 | V51 迁移、`ModelSecretCipher` | 无 | 已验收 | 集成 | 同上 |
| 模型能力探测 | `AiCapabilityProbeService` | 无 | 已实现 | 单测/集成 | 本轮快照代码 |
| Ollama 本地 Embedding（含本地目标白名单） | `EmbeddingEndpointPolicy`、`embedding.local-allowed-targets` | 无 | 已验收 | 集成 + 真实模型 | `real-ollama-index-active.png` |
| Embedding 索引代际（候选/构建/显式激活） | V46/V48、`EmbeddingIndexService` | 无 | 已验收 | 集成 | `EmbeddingGenerationIntegrationTest` |

## 2. Agent Runtime

| 功能 | 关键组件 | 模型要求 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 运行租约、崩溃恢复、防重入 | `AgentRecoveryJob`、`AgentLeaseScope`、V49 计数器 | 无 | 已验收 | 集成 | `AgentRepositoryIntegrationTest` |
| 运行预算（步数/轮数/工具数/时长/输入输出 token） | `AgentRuntimeLimits`、`AgentWorker` 预检、`AgentRuntimeCoordinator` | 无 | 已验收 | 单测 + 集成 | `AgentRuntimeLimitsTest` 等 |
| 事件流（SSE）与持久化事件双写 | `AgentEventStreamService`、`AgentRunEventRecorder`、V28 | 无 | 已验收 | 集成 | `AgentEventRepositoryIntegrationTest` |
| 跨 Tick 工具调用恢复（消息协议配对） | `AgentModelMessageComposer.rebuildToolMessagesFromSteps` | 需原生 Tool Calling | 已验收 | 单测 + 集成 | `CrossTickToolCallTest` |
| 工作状态（结构化约束/目标/本轮要求） | `AgentWorkingState` v2（V47 JSONB，schemaVersion=2 渐进升级） | 无 | **P1 已交付**：五分类约束（持续/修改/本轮/普通/新目标）、stateRevision、来源消息 ID；摘要节点已启用 | 集成 | `AgentRepositoryIntegrationTest` |
| 每轮上下文组装 | `AgentModelMessageComposer`（v2 分层 + `AgentContextBudget`） | 无 | **P1 已交付**：单次请求预算分层、当前请求保护、大结果确定性投影、去重、降级重组；`agent.context.composer-v2` 可关闭（回退路径兼容 v2 数据） | 单测 + 集成 | `AgentModelMessageComposerV2Test`、`AgentContextBudgetTest` |
| 有界增量会话摘要 | `AgentContextSummarizer`（working_state.summary 节点） | 原生 Tool Calling 模型 | **P1 已交付**：有界输入/输出、覆盖范围来自实际输入（FULL/PARTIAL）、增量延续旧摘要、CAS（revision 匹配 + 原子递增 + 边界校验）、持久化尝试标记（每运行至多一次）、单独记账且 usage 缺失按保守估算、失败不影响主轮次 | 单测 + 集成 | `AgentContextSummarizerTest` |
| Skill 路由（含复合规划意图） | `AgentSkillRegistry` | 无 | 已实现：复合规划优先进入迭代规划；保留单项提案与只读边界 | 单测 + 真实模型 | `AgentSkillRegistryTest`、交付报告 P3/P4 |
| 项目记忆（按当前请求相关性选择） | `AgentMemoryService`、V30 | 无 | 已实现；历史记忆不是当前事实，质量仍按具体场景评测 | 单测 + 集成 | `AgentReadToolsTest`、交付报告 P4 |
| 原生 Tool Calling 与 Legacy 只读降级 | `RoutingAgentModelExecutor`、`LegacyReadOnlyAgentExecutor` | 原生工具需模型支持 | 已验收 | 单测 + 真实模型 | `AgentWorkerNativeTurnTest` |

## 3. Agent 工具与写路径

| 功能 | 关键组件 | 角色 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 只读查询（任务/成员/里程碑/文档检索等） | `TaskListAgentTool`、`KnowledgeSearchAgentTool` 等 | 成员 | 已实现并专项验收：任务标题/关键词、过滤、keyset 分页、total/hasMore/truncated；并发分页为实时快照 | 单测 + 真实数据库 + 真实模型 | `AgentReadToolsTest`、交付报告 P2/P4 |
| 提案写路径（数值校验、参数合并） | `CreateTaskApprovalAgentTool`、`UpdateTaskApprovalAgentTool` | 成员 | 已验收 | 集成 | `AgentWriteProposalSpringIntegrationTest` |
| 提案修订与版本冲突 | `AgentApprovalService`（revision、409） | 成员 | 已验收（真实修订 1→2、旧版本 409） | 集成 + 浏览器 | `space-bunny-acceptance-report` |
| 审批幂等与过期 | `AgentApprovalController`、审批 nonce | 成员 | 已验收（同键幂等批准）；**缺口**：`AGENT_APPROVAL_EXPIRED` 浏览器提示未真实制造 | 集成 + 浏览器 | 同上 |
| MCP 白名单接入 | `McpAgentToolProvider`、`agent.mcp.allowed-hosts` | 管理员管理 | 已验收 | 集成 | `McpAdministration*Test` |
| 用户输入暂停/续答（pendingQuestion） | `RequestUserInputAgentTool`、`continueRun` | 成员 | 已实现 | 集成 | 本轮快照代码 |

## 4. 任务规划（planning 模块）

| 功能 | 关键组件 | 角色 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 规划生成（骨架/详情分阶段、草稿版本） | `TaskPlanCommandService`、`TaskPlanGenerationOrchestrator` | 管理员 | 已验收（真实双模型完整规划） | 集成 + 真实模型 | `real-planning-ling.png` 等 |
| 结构化校验与局部修复 | `TaskPlanOutputParser`、`TaskPlanPartialRepairService`、V53 | 管理员 | 已验收 | 集成 | `TaskPlanRepairPatchTest` |
| 人工确认与事务落库 | `TaskPlanConfirmationService` | 管理员 | 已验收（浏览器确认链路、防重复提交） | 集成 + 浏览器 | `space-bunny-browser-A/B` 系列 |
| Agent 调用规划工具 | `AgentPlanningOperationService`、V55 | 管理员/所有者交互运行 | 已实现：生成、修订、读取、取消；受理与完成分开，人工确认仍在规划页 | 集成 + 真实模型 + 浏览器 | 交付报告 P3/P4、long-quality-fix |
| 规划上下文装配 | `TaskPlanContextAssembler` | — | **缺口**：业务事实集合无裁剪（蓝图第 2 节） | 单测 | 现有代码 |

## 5. 文档与知识

| 功能 | 关键组件 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- |
| 文档上传解析（Tika、PDF 页码、原件 MinIO） | `TikaDocumentParser`、`DocumentProcessingService` | 已验收（真实文档 RAG）；OCR/复杂表格**不在范围** | 集成 + 真实模型 | `real-document-persistent.png` |
| 分块与 pgvector 检索、正文按需读取 | `DocumentChunker`、`DocumentSearchService`、正文快照/目录/章节工具 | 已实现：目录、提纲、有界正文、续读与快照引用；检索仅代表命中片段，正文独立于向量化 | 集成 + 真实模型 + 浏览器 | 交付报告 P2、long-quality-fix；旧块不补造页码 |
| 索引代际与批重建 | `BatchReindexService`、V50 | 已验收 | 集成 | `BatchReindexAuthorizationTest` |
| 知识问答（检索、引用校验、追问） | `KnowledgeQuestionApplicationService`、`KnowledgeConversationContext` | 已验收（追问链路）；**待评测**：多段证据/中文术语召回（蓝图 5.3） | 集成 + 真实模型 + 浏览器 | `real-knowledge-followup.png` |

## 6. P1 验收用例（蓝图第 9 节场景 1–3 细化）

> P1 完成时逐项在此表登记结果。权限/版本/重复写入为硬性门槛。

| # | 场景 | 输入要点 | 通过标准 | 对应测试 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 长对话早期约束保留 | 20–30 轮对话，第 1–2 轮含"最多十项、不改日期"，中间穿插覆盖约束与无关问答 | 早期约束仍在模型输入中（active 条目或摘要）；"最多十项→八项"后旧条目 superseded；active 条目不被条数上限淘汰；上下界可并存 | `recognizedPersistentConstraintsStayActiveAcrossOrdinaryMessages`（20 轮）、`constraintUpdateSupersedesOldEntryWithinSameScope`、`activeConstraintsSurviveConstraintChurnWithoutEviction`、`minAndMaxTaskCountConstraintsCoexistWithoutSupersedingEachOther` | 通过（集成；20–30 轮真实模型长对话待 P4 评测） |
| 2 | 当前请求保护 | 当前请求较长且关键否定条件在尾部；低窗口模型配置 | 关键否定条件不静默截断；超预算返回明确原因（区分超接口上限/超单次输入/超运行费用）；缺失 usage 可诊断 | `currentRequestSurvivesTinyHistoryBudgetButNotRequiredLayerOverflow`、`AgentContextBudgetTest`（原因码）、窗口覆盖估算标记 | 通过（单测/集成；低窗口真实模型待 P4 评测） |
| 3 | 工具结果混合压缩 | 大结果、失败结果、未完成调用并存于同一运行 | 压缩不伪造成功、toolCallId 配对完整、未决调用不压缩、不重复写入 | `largeToolOutputsAreProjectedDeterministicallyAndStayPaired`、`smallToolOutputsRemainUnprojected`、`CrossTickToolCallTest` | 通过（投影仅作用于已完成结果；未决调用在组装前由批次执行处理，不进入压缩路径） |
| 4 | 改目标不串任务 | 对话途中 `/replace` 或同义替换词切换目标，旧规划稍后完成 | 新目标生效，旧约束 superseded；旧目标历史保留 | `userSupplementPreservesGoalAndExplicitReplacementKeepsHistory` | 通过（替换词表为保守集合；模糊表述不触发替换，原话保留） |
| 5 | 回退 | 已写入 v2 working state + 摘要的会话，`agent.context.composer-v2=false` | 继续对话不丢记录、不报错、不误认当前目标；无法完整表达有效约束时明确停止 | `legacyFallbackPathUnderstandsV2WorkingStateAndSummary`、`FAILURE_CURRENT_REQUEST_OVER_BUDGET` | 通过 |

P1 补充验收（本轮修订新增口径）：

| 口径 | 覆盖测试 | 结果 |
| --- | --- | --- |
| 修改同一约束（十项→八项，旧条目 superseded） | `constraintUpdateSupersedesOldEntryWithinSameScope` | 通过 |
| 无关问答不积累成约束（含本轮要求重算） | `ordinaryQuestionsAndTurnRequirementsDoNotAccumulateAsConstraints` | 通过 |
| 摘要 CAS：原子递增 stateRevision、revision 前进后旧摘要拒绝、伪造边界拒绝、只改 summary 节点 | `summaryCasCommitOnlyUpdatesSummaryNodeAndRejectsStaleRevision` | 通过 |
| 摘要失败/异常不影响主轮次；CAS 冲突不重算；持久化尝试上限 | `AgentContextSummarizerTest` | 通过 |
| 约束替换绑定作用域（文档引文/否定句/举例不触发） | 仅确定性作用域正则触发；`isGoalReplacement` 仅保守词表 | 通过（代码审查 + 保守词表） |
| appendUser 关联真实消息 ID（同事务） | `appendUserAssociatesRealMessageIdWithinTransaction` | 通过 |

## 7. 历史证据索引

- 最新验收：[space-bunny-acceptance-report.md](./archive/reports/space-bunny-acceptance-report.md)（2026-10-03；后端 904 项：899 通过 5 跳过，前端 135 项 + vue-tsc + vite build）
- 证据附件：`docs/acceptance-evidence/2026-10-03/`（隔离副本脱敏产物，无凭据；内含 localhost 副本端口属预期）
- 更早轮次：`ai-refactor-real-acceptance-report.md`（真实 Zen JSON、原生工具、双模型规划、真实文档 RAG）、`ai-refactor-quality-report.md`（负责人建议、V53）
- 遗留未验证项（承接上一阶段）：`AGENT_APPROVAL_EXPIRED` 浏览器提示、拒绝路径浏览器冲突、大规模并发/长时评测、正式环境发布与迁移

## 8. 下一阶段增量验证（2026-10-04）

| 能力 | 当前状态 | 验证 | 证据 |
| --- | --- | --- | --- |
| P1 摘要持续覆盖与窗口外续读 | 已实现；策略 v4，偏移合并、完成前缀压缩、删除终止、重读鉴权 | 单测 + 真实隔离 PostgreSQL 数据流，61 项通过 | [P1 修复](./archive/reports/p1-context-foundation-report.md#11-剩余覆盖修复2026-10-04) |
| P2 任务搜索分页、文档目录/提纲/正文续读、可靠来源 | 已实现；任务 keyset 实时分页，正文独立于 embedding；V54，旧来源兼容读取 | 43 项后端针对性（含 PostgreSQL），137 项前端；类型检查通过 | [连续交付报告](./archive/reports/next-stage-delivery-report.md) |
| P3 Agent 接通规划生成/查询/修复/取消 | 已实现；V55 操作身份、提交后 dispatch、同 attempt 恢复；原规划页人工确认 | 93 项针对性（含 PostgreSQL）、139 项前端、类型检查；真实模型另记 | [连续交付报告](./archive/reports/next-stage-delivery-report.md#p3) |
| P4 业务评测、相关记忆、追问与质量收敛 | 已实现 17 场景评测；修复真实规划链路预算/身份/修订意图/延迟事件关联；未引入无证据的检索融合 | 最终 962 后端：956 通过/6 跳过，139 前端及构建；真实 space-bunny-free 生成/修订、API 用户确认、PG 不变量；真实向量 4/4；浏览器未验证 | [交付与限制](./archive/reports/next-stage-delivery-report.md#p4)、[执行清单](acceptance-evidence/2026-10-04/p4-backend-test-manifest.json) |
| 复核边界修复：详情恢复鉴权、持久输出预算、卡片退避、列表同步范围 | 已实施；先前未验证记录保留为历史，浏览器指定链路已补验收 | PostgreSQL 撤权恢复/网关实际预算参数/列表隔离；真实内置浏览器 503 自动恢复、失败上限、手动恢复 | [复核修复](./archive/reports/next-stage-delivery-report.md#复核后的边界修复2026-10-04)、[浏览器补验收](./archive/reports/next-stage-delivery-report.md#内置浏览器补验收2026-10-04) |
| 浏览器资料→Agent 规划→同规划局部修订→人工确认→刷新核对 | 已通过所列链路；修复原生来源卡片持久化及修复工具 mode/字段契约 | 生产适配器 space-bunny-free，真实浏览器人工确认 v4；PG 4 任务/2 里程碑/3 依赖；最终 966 后端：960 通过/6 跳过，142 前端及构建 | [操作、截图、API 与 PG 证据](acceptance-evidence/2026-10-04/browser/)、[范围与未验证项](./archive/reports/next-stage-delivery-report.md#内置浏览器补验收2026-10-04) |
