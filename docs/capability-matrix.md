# 能力矩阵

## 2026-11-14 当前全能力与开关台账（在用 / 可配置开启 / 冻结储备）

> 本节的“设计前基线”表格记录 `dev @ d2a8517b` 的历史状态，仅作追溯；当前结论以
> 下方“2026-11-14 本地接线收口”台账为准。Wave 1 接线与 Wave 2 真进程联调均已完成，
> 生产/CI 默认仍保持关闭。
> 详细设计与执行证据见 [local-system-wiring-and-verification-2026-11-14.md](design/local-system-wiring-and-verification-2026-11-14.md)
> 、[Wave 2 真进程证据](acceptance-evidence/2026-11-14/local-wiring/wave2/README.md)
> 和 [local-wiring/report.md](acceptance-evidence/2026-11-14/local-wiring/report.md)。

### 2026-11-14 本地接线收口（Wave 2 已完成）

| 能力 | 生产/CI 默认（仓库） | 本轮事实 | local 默认 / 限制 |
| --- | --- | --- | --- |
| 专用 Reranker + top-8 inline | `retrieval.configured-rerank.enabled=false`、`retrieval.inline-rerank-top8=false` | BSK 发布并绑定 v3 档案；Agent RAG_V2 真实调用 ConfiguredReranker；9 样本 P95 `280ms`，top-8 样本 `candidates=8`，无 `inline_candidate_limit` | local 默认开启；实测 `MEASURED_LOCAL` |
| RAG 策略路由 | `retrieval.qa-mode=DENSE_LEGACY` | `DENSE_WITH_BOUNDED_FALLBACK`、`HYBRID`、`MULTI_QUERY_HYBRID` 三条路径均真进程命中 | local 默认 `STRATEGY_AWARE` |
| QueryPlan 接入 | `QUERY_PLAN_ENABLED=false` | 真实 worker `ADOPTED confidence=0.62`；低置信度/超时 stub 均安全回退 | local 默认关闭，按需 `QUERY_PLAN_ENABLED=true` |
| 本机回环白名单 | `ai.outbound.local-allowed-targets=""` | 真进程使用 `127.0.0.1:3425` 调本机 Magpie；未改生产默认 | local 默认精确放行 `127.0.0.1:3425` |
| demo6 任务分页 | 不改变业务默认 | Wave 1.5 改造后 JIT=on HTTP P95 `20.228ms`（10k 任务、200 条、102,311 bytes）；JIT 保持 on | `MEASURED_LOCAL`；更大数据量仍建议索引/keyset |
| ArchUnit 最小护栏 | 仅 test scope | 3 条规则 + 5 条历史 domain→infrastructure 边显式冻结；全量 Java 门禁绿 | 无 |
| 前端项目记忆抽屉 | `MEMORY_JOB_ENABLED=false` | BSK 真 UI 触发 Job、审批候选；Ed25519 `/v1/jobs` `SUCCEEDED`；下轮 Agent 召回两条 ACTIVE 记忆 | local 默认关闭；按需 `MEMORY_JOB_ENABLED=true` + Ed25519 |
| F1 Decide v4 | 不改变运行默认 | v1/v4 真 worker 采数：golden 一致率 `0.95` / `0.953125`，JSON 合法率 `1.0`；`qualityClaim=NOT_VERIFIED`，adversarial rejectedRate `0.0` 不作为质量结论 | `MEASURED_LOCAL` |
| F2 Decide 真 Worker | `DECIDE_MODE=java` | shadow 1 次 CALL_TOOL agreement=match；python `python:CALL_TOOL`、`python_fallback:decision_final`、停 worker `python_fallback:worker_unavailable` | local 默认 java；按需 shadow/python |
| G Planning 真 Worker | `PLANNING_DRAFT_MODE=java` | shadow 成功 9.4s、schema pass；python `python:SKELETON`、Java detail/reflect、确认后 3 任务；worker 422/停机均回退 | local 默认 java；按需 shadow/python |
| 服务令牌 Planning scope | `INTERNAL_AI_TOKEN_MODE=static` | 修复 `SERVICE_SCOPES` 缺少 `planning` 导致的 Ed25519 401；定向 18 项 exit=0 | 生产默认不变；Ed25519 模式已可用 |
| Wave 2 集成栈 | 保持生产默认 | llama-server 18081、ai-worker 19100、隔离后端 18095、前端 15177 全部真进程跑通 | 资源已在收口时清理 |
| Java 全量门禁 | — | 集成后 `3123 tests, 0 failures, 0 errors, 61 skipped, exit=0`（23:08 min） | 未删除/修改失败用例或阈值 |

**开关与迁移**：仓库生产/CI 默认保持 `RERANK_MODE=java`、`DECIDE_MODE=java`、`PLANNING_DRAFT_MODE=java`、
`MEMORY_JOB_ENABLED=false`、`INTERNAL_AI_TOKEN_MODE=static`、`AI_WORKER_TOKEN_MODE=static`、
`AGENT_MCP_GATEWAY_ENABLED=false`、`MQ_ENABLED=false`、`QUERY_PLAN_ENABLED=false`；
local profile 只额外打开 `STRATEGY_AWARE`、`configured-rerank + inline-rerank-top8` 与
`127.0.0.1:3425` 白名单；其他能力仍由 `.env` 按需开启。迁移仍为 V1–V101，
未新增或改写历史迁移。

### 设计前基线（保留）

| 能力域 | 默认状态 | 开关 / 入口 | 代码实现与证据状态 | 说明 |
| --- | --- | --- | --- | --- |
| 文档删除任务化（V100） | **默认在线** | — | **已验收（本机 demo）** | 同事务标记 `DELETING` + 入队；MinIO 停机、DB 失败、杀进程恢复已实测 |
| JWT `tv` 撤销与会话清理 | **默认在线** | `JWT_ACCESS_TOKEN_MINUTES`（代码默认 15） | **已验收（集成 + demo）** | 停用/改密/重置/`logoutAll` 同步撤销；本机 `.env` 仍覆盖为 `30` |
| Agent 审批收件箱轮询 | **默认在线** | — | **已验收（BSK + 单测）** | 终态 + PENDING 每 5s 增量轮询；后台标签暂停目前为单测证据 |
| 任务列表批量化与分页（V101） | **默认在线** | `?page=&size=` | **有条件通过** | 服务层 10k P95 `29ms`、SQL=`2`；真实 HTTP P95 `346ms` 待拆因 |
| 问答检索主链 | **默认 `DENSE_LEGACY`** | `ProjectKnowledgeRetrieval.Mode` | **默认在线** | `StrategyAwareRetrievalExecutor` 暂未接入主链，待下轮按开关接通 |
| 专用 Reranker 配置平台（V92–V95 + N5 UI） | **默认未接入主链** | `RerankerType.CONFIGURED_RERANK` | **后端/UI/本机模型就绪** | 业务路径未传 `CONFIGURED_RERANK` 且 `>8` 候选会触发限流，待下轮 Top-8 接通 |
| Python Rerank 路由（W3） | **`RERANK_MODE=java`** | `RERANK_MODE=java\|shadow\|python` | **主链实现；shadow 待实测** | `Agent` 知识工具才进 `RetrievalPipeline`；LLM 打分 P95 ~4.8s，只适于 shadow/诊断 |
| Python Decide 路由（W4/F2） | **`DECIDE_MODE=java`** | `DECIDE_MODE=java\|shadow\|python` | **Java 受限主链 + Prompt v5 就绪** | 仅过 stub/集成；真 `ai-worker` 双跑与 F1 80 条复核待下轮补齐 |
| Python Planning Skeleton 路由（W5/G） | **`PLANNING_DRAFT_MODE=java`** | `PLANNING_DRAFT_MODE=java\|shadow\|python` | **Java 受限主链就绪** | 仅过 stub/集成；真 `ai-worker` 双跑待下轮补齐 |
| Python Query Plan（W4） | **`QUERY_PLAN_ENABLED=false`** | `QUERY_PLAN_ENABLED` | **仅客户端** | 待下轮接入 `QueryRouter` 可选增强并测耗时/收益 |
| Memory Dreaming Job（W5） | **`MEMORY_JOB_ENABLED=false`** | `MEMORY_JOB_ENABLED` + `INTERNAL_AI_TOKEN_MODE=ed25519` | **staging E2E 通过；缺前端入口** | 待下轮补前端最小抽屉并用本地 Ed25519 跑通 |
| RocketMQ 三链路双轨 | **`MQ_ENABLED=false`** | `MQ_ENABLED` / `PLANNING_MQ_ENABLED` / `AGENT_MQ_ENABLED` | **本地/staging 可开** | 单机 demo 不需要，保持进程内兜底 |
| W6 MCP Gateway / Python Host | **已冻结（`CLOSED_AS_DEFAULT_OFF`）** | `AGENT_MCP_GATEWAY_ENABLED=false` | **功能与安全矩阵完备；未上生产** | [w6-production-decision-2026-11-14.md](archive/report/w6-production-decision-2026-11-14.md) |

## 2026-10-20 当前基线与 P3 规划入口

| 当前增量事实 | 验证层级与限制 | 证据 |
| --- | --- | --- |
| P7 Provider 接入自动化：探针专用执行边界（未声明能力真实可测、声明与验证分离）、「检测并配置」编排 + CAS 写回（只增不减、版本锚点 rebase）、URL/Path 重复版本前缀三层防线、ModelScope 档案与未保存模型发现、能力验证状态表七态展示 | 单测 + 真实 PostgreSQL 集成 + 真实浏览器/真实 ModelScope/Gemini（未声明 NATIVE_TOOLS 真实探针 PASS 并自动启用）；Anthropic/OpenAI 真实付费调用未执行；检测锁为 JVM 内实现 | [P7-PROVIDER-ONBOARDING-01 验收](acceptance-evidence/2026-10-25/p7-provider-onboarding-01/README.md) |
| **J2 PromptPack 接入**：Prompt 正文迁 Python 版本化资产（14 条 key），Java 新增 `PromptCatalog`（启动预取 + 进程内缓存 + 二次 sha256 校验 + 失败保留上次成功版本 + 快速失败 `AI_PROMPT_UNAVAILABLE`），删除 `KnowledgePromptProvider` 与六个 Scenario 的 instruction/outputContract 正文；`promptVersion` 可在 Run 快照与 `knowledge_message` 追溯；开关默认关闭 = 读内置只读 fallback 包（同一份字节） | 单测/契约测试全绿（Java 同文本一致性以改动前导出的 golden 夹具为准，Python 以冻结指纹表为准）；**未经真实 worker 联调**、未做灰度；两来源等价性仅在同一份资产上验证 | [J2 交付报告](archive/report/j2-prompt-pack-20261111.md) |
| **J3/P1 rerank 接入**：Python 新增 `POST /v1/rag/rerank`（50 候选/2000 字符上限、deadline 默认 3s、幂等 10 分钟、覆盖全部 id）与唯一的 Java 内部模型网关客户端；Java 新增 `rerank.mode=java\|shadow\|python`（默认 java 行为不变、shadow 非阻塞只打点、python 失败回退 + `PARTIAL_SCORE`）、`RerankGatewayClient` 与 `/internal/ai/v1/model-calls`；compose 新增 `ai` internal 网与无宿主端口的 worker | 489 项 Python + 55 项 W3 Java + 全量 2704 项绿；staging 真实模型打通：shadow top-8 重合率 1.0、用户可见 rerank 延迟 1.5ms；冻结集真实模型 NDCG@8 0.9791 vs Java 0.9614、支持率持平；**P95 e2e 5.05s 未达 800ms 门禁**；真实用户 canary `NOT_CLAIMED`；Ed25519 短令牌遗留 | [J3 交付报告](archive/report/j3-rerank-20261112.md) |

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
| 跨 Tick 工具调用恢复（消息协议配对） | `AgentModelMessageComposer` 的 v2 工具观察层（`appendToolPair`） | 需原生 Tool Calling | 已验收 | 单测 + 集成 | `CrossTickToolCallTest` |
| 工作状态（结构化约束/目标/本轮要求） | `AgentWorkingState` v2（V47 JSONB，schemaVersion=2 渐进升级） | 无 | **P1 已交付**：五分类约束（持续/修改/本轮/普通/新目标）、stateRevision、来源消息 ID；摘要节点已启用 | 集成 | `AgentRepositoryIntegrationTest` |
| 每轮上下文组装 | `AgentModelMessageComposer`（v2 分层 + `AgentContextBudget`） | 无 | **P1 已交付**：单次请求预算分层、当前请求保护、大结果确定性投影、去重、降级重组；**组装路径固定 v2**（2026-11-11 J1-3 起删除 `composer-v2=false` 回退，历史 v2 数据只读兼容、无需迁移） | 单测 + 集成 | `AgentModelMessageComposerV2Test`、`AgentContextBudgetTest` |
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
| 知识问答（检索、引用校验、追问） | `KnowledgeQuestionApplicationService`、`KnowledgeConversationContext`、`KnowledgePromptAssembler`、`PromptCatalog` | 已验收（追问链路）；**待评测**：多段证据/中文术语召回（蓝图 5.3）；**Prompt 来源**：J1-4 先收敛到 `KnowledgePromptProvider` 临时单例，J2 已把正文迁到 Python PromptPack（`knowledge.system@2026-11-11.2`、`knowledge.user@2026-11-11.1`）并删除该类，Java 侧只保留时间行注入、XML 转义与会话上下文前缀；`prompt.registry.enabled=false` 时读内置只读 fallback 包，两种来源产出逐字节相同的正文 | 集成 + 真实模型 + 浏览器 | `real-knowledge-followup.png`、`J2PromptPackConsistencyTest`、`J2PromptPackFailFastTest` |

## 6. P1 验收用例（蓝图第 9 节场景 1–3 细化）

> P1 完成时逐项在此表登记结果。权限/版本/重复写入为硬性门槛。

| # | 场景 | 输入要点 | 通过标准 | 对应测试 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 长对话早期约束保留 | 20–30 轮对话，第 1–2 轮含"最多十项、不改日期"，中间穿插覆盖约束与无关问答 | 早期约束仍在模型输入中（active 条目或摘要）；"最多十项→八项"后旧条目 superseded；active 条目不被条数上限淘汰；上下界可并存 | `recognizedPersistentConstraintsStayActiveAcrossOrdinaryMessages`（20 轮）、`constraintUpdateSupersedesOldEntryWithinSameScope`、`activeConstraintsSurviveConstraintChurnWithoutEviction`、`minAndMaxTaskCountConstraintsCoexistWithoutSupersedingEachOther` | 通过（集成；20–30 轮真实模型长对话待 P4 评测） |
| 2 | 当前请求保护 | 当前请求较长且关键否定条件在尾部；低窗口模型配置 | 关键否定条件不静默截断；超预算返回明确原因（区分超接口上限/超单次输入/超运行费用）；缺失 usage 可诊断 | `currentRequestSurvivesTinyHistoryBudgetButNotRequiredLayerOverflow`、`AgentContextBudgetTest`（原因码）、窗口覆盖估算标记 | 通过（单测/集成；低窗口真实模型待 P4 评测） |
| 3 | 工具结果混合压缩 | 大结果、失败结果、未完成调用并存于同一运行 | 压缩不伪造成功、toolCallId 配对完整、未决调用不压缩、不重复写入 | `largeToolOutputsAreProjectedDeterministicallyAndStayPaired`、`smallToolOutputsRemainUnprojected`、`CrossTickToolCallTest` | 通过（投影仅作用于已完成结果；未决调用在组装前由批次执行处理，不进入压缩路径） |
| 4 | 改目标不串任务 | 对话途中 `/replace` 或同义替换词切换目标，旧规划稍后完成 | 新目标生效，旧约束 superseded；旧目标历史保留 | `userSupplementPreservesGoalAndExplicitReplacementKeepsHistory` | 通过（替换词表为保守集合；模糊表述不触发替换，原话保留） |
| 5 | 历史 v2 数据兼容（只读） | 已写入 v2 working state + 摘要的会话，不再有组装开关，全部 Run 固定 v2 | 继续对话不丢记录、不报错、不误认当前目标；无法完整表达有效约束时明确停止 | `composeV2InjectsExistingConversationSummary`、`FAILURE_CURRENT_REQUEST_OVER_BUDGET` | 通过 |

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
