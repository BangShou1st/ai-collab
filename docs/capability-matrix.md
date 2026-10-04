# 能力矩阵

维护约定（依据 [ai-next-stage-blueprint.md](ai-next-stage-blueprint.md) 8.3）：

- 本表记录**当前 checkout** 的能力状态；历史审查（`ai-capability-review.md`）与验收报告只作为证据链接，不混列成当前待办。
- 验证层级：`单测`（Mockito 纯单测）→ `集成`（Testcontainers 真实 PostgreSQL）→ `真实模型`（授权副本上的真实提供商验收）→ `浏览器`（产品界面实际操作）。高层级不自动覆盖低层级未覆盖的分支。
- 状态口径：`已验收`＝有对应证据；`缺口`＝蓝图已确认待处理；`待评测`＝功能存在但质量未用固定用例度量。

最后更新：2026-10-04（四类长对话失败修复，分支 `codex/context-foundation`）。当前质量结果见顶部专项记录；工具可调用不等于整个业务能力全面验收。

本轮修复与真实质量结果见 [四类失败专项报告](acceptance-evidence/2026-10-04/long-quality-fix/report.md)。真实 space-bunny-free 固定长对话已复验；部分核心失败改善，但实际输入 usage 超预算仍被计数上限遮蔽、摘要仍误把当前数量修改列为待确认，读取范围也有残余错误，**整体质量未通过，不满足部署验收条件**。后端全量 982 项：971 通过、11 显式 opt-in 跳过、零失败/错误；真实 PostgreSQL 已执行。前端 143 项、类型检查、构建及后端打包通过。开发分支推送仅用于备份，不代表正式发布。此前可靠性故障恢复证据保留，不重新认定为待开发。

## 1. 基础设施与模型接入

| 功能 | 关键组件 | 模型要求 | 状态 | 验证层级 | 证据 |
| --- | --- | --- | --- | --- | --- |
| 多提供商模型路由（OpenAI 兼容 / OpenCode Zen / Ollama） | `infrastructure/ai/model/Routing*`、`ProviderPresetRegistry` | 按用户与用途路由 | 已验收 | 集成 + 真实模型 | [space-bunny-acceptance-report](space-bunny-acceptance-report.md) |
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

- 最新验收：[space-bunny-acceptance-report.md](space-bunny-acceptance-report.md)（2026-10-03；后端 904 项：899 通过 5 跳过，前端 135 项 + vue-tsc + vite build）
- 证据附件：`docs/acceptance-evidence/2026-10-03/`（隔离副本脱敏产物，无凭据；内含 localhost 副本端口属预期）
- 更早轮次：`ai-refactor-real-acceptance-report.md`（真实 Zen JSON、原生工具、双模型规划、真实文档 RAG）、`ai-refactor-quality-report.md`（负责人建议、V53）
- 遗留未验证项（承接上一阶段）：`AGENT_APPROVAL_EXPIRED` 浏览器提示、拒绝路径浏览器冲突、大规模并发/长时评测、正式环境发布与迁移

## 8. 下一阶段增量验证（2026-10-04）

| 能力 | 当前状态 | 验证 | 证据 |
| --- | --- | --- | --- |
| P1 摘要持续覆盖与窗口外续读 | 已实现；策略 v4，偏移合并、完成前缀压缩、删除终止、重读鉴权 | 单测 + 真实隔离 PostgreSQL 数据流，61 项通过 | [P1 修复](p1-context-foundation-report.md#11-剩余覆盖修复2026-10-04) |
| P2 任务搜索分页、文档目录/提纲/正文续读、可靠来源 | 已实现；任务 keyset 实时分页，正文独立于 embedding；V54，旧来源兼容读取 | 43 项后端针对性（含 PostgreSQL），137 项前端；类型检查通过 | [连续交付报告](next-stage-delivery-report.md) |
| P3 Agent 接通规划生成/查询/修复/取消 | 已实现；V55 操作身份、提交后 dispatch、同 attempt 恢复；原规划页人工确认 | 93 项针对性（含 PostgreSQL）、139 项前端、类型检查；真实模型另记 | [连续交付报告](next-stage-delivery-report.md#p3) |
| P4 业务评测、相关记忆、追问与质量收敛 | 已实现 17 场景评测；修复真实规划链路预算/身份/修订意图/延迟事件关联；未引入无证据的检索融合 | 最终 962 后端：956 通过/6 跳过，139 前端及构建；真实 space-bunny-free 生成/修订、API 用户确认、PG 不变量；真实向量 4/4；浏览器未验证 | [交付与限制](next-stage-delivery-report.md#p4)、[执行清单](acceptance-evidence/2026-10-04/p4-backend-test-manifest.json) |
| 复核边界修复：详情恢复鉴权、持久输出预算、卡片退避、列表同步范围 | 已实施；先前未验证记录保留为历史，浏览器指定链路已补验收 | PostgreSQL 撤权恢复/网关实际预算参数/列表隔离；真实内置浏览器 503 自动恢复、失败上限、手动恢复 | [复核修复](next-stage-delivery-report.md#复核后的边界修复2026-10-04)、[浏览器补验收](next-stage-delivery-report.md#内置浏览器补验收2026-10-04) |
| 浏览器资料→Agent 规划→同规划局部修订→人工确认→刷新核对 | 已通过所列链路；修复原生来源卡片持久化及修复工具 mode/字段契约 | 生产适配器 space-bunny-free，真实浏览器人工确认 v4；PG 4 任务/2 里程碑/3 依赖；最终 966 后端：960 通过/6 跳过，142 前端及构建 | [操作、截图、API 与 PG 证据](acceptance-evidence/2026-10-04/browser/)、[范围与未验证项](next-stage-delivery-report.md#内置浏览器补验收2026-10-04) |
