# J0-4 检索门面（ProjectKnowledgeRetrieval）设计与行为说明

- 日期：2026-11-11
- 分支：`j0-4-retrieval`（基于 `dev` @ `61be76e2`）
- 范围：后端检索入口收敛，不涉及 Flyway 迁移、前端、Python 侧车，也不涉及 J0-1/J0-2/J0-3。

## 1. 新增门面

新增 `com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval`（`@Service`），
构造器强制注入 `ProjectAccessGuard`、`DocumentSearchService`、`RetrievalPipeline`、`QueryRouter`。

统一入口：

- `retrieve(Request)`：`Request(projectId, userId, query, documentIds, topK, mode)`。
  - 先校验 `projectId/userId/query/mode/topK(1..20)`，再执行 `access.requireMember(projectId, userId)`；
    权限校验先于路由、嵌入与管线执行。
  - `documentIds` 的 `null` 与空列表统一规范化为 `List.of()`，元素去重并剔除 `null`。
    `DocumentMapper` 的 `documentIds != null and !documentIds.isEmpty()` 条件使 null/空列表语义等价，
    因此不改变检索范围。
  - `DENSE_LEGACY`：直接委托 `DocumentSearchService.search(...)`，保留显式 documentIds 的项目内
    READY 校验、EmbeddingBatch 查询向量快照与仓储层 READY/撤权过滤。
  - `RAG_V2`：`QueryRouter.route(query)` 解析结构化过滤与语义查询，将 filters 与 topK 下推
    `RetrievalPipeline.execute(...).toDocumentSearchHits()`。
  - `Result(hits, effectiveQuery, filters, retrievalCore)` 的 `retrievalCore` 固定为
    `LEGACY_DENSE` 或 `RAG_V2_PIPELINE`。
  - `hits` 是唯一规范引用身份来源（documentId/chunkId/filename/heading/content/similarity/
    contentHash/metadata），门面只透传，不生成、不重排、不改写 ID。
- `resolveUserDocumentScope(projectId, userId, userGoal)`：先 `requireMember`，再委托
  `DocumentSearchService.resolveUserDocumentScope`。中文来源正则原样保留，并标注
  `TODO(J1): 中文来源正则与自然语言范围解析迁出；依赖前端文档选择器与显式 documentIds。`

## 2. 四个调用方迁移

| 调用方 | 迁移前 | 迁移后 |
| --- | --- | --- |
| `KnowledgeSearchAgentTool` | 可选注入 pipeline/router，缺失时静默回退 `DocumentSearchService` | 构造器仅依赖门面；固定 `RAG_V2`、候选池 20；权限/范围解析/引用身份均经门面 |
| `KnowledgeQuestionApplicationService` | 未注入的 `RetrievalPipeline` + 更早的 `DocumentSearchService` 分支 | 移除死字段与死分支，固定 `DENSE_LEGACY` 门面调用 |
| `KnowledgeStreamQuestionService` | 同上 | 同上，提示词/证据装配/拒答/时延日志不变 |
| `SearchProjectKnowledgePlanningTool` / `TaskPlanContextAssembler` | 直接 `DocumentSearchService.search`（规划工具另持 `ProjectAccessGuard`） | 固定 `DENSE_LEGACY` 门面调用，权限由门面强制 |

QA 与流式 QA 的 `RetrievalPipeline` 字段确认从未被注入（无 `@Autowired`、无 setter、无反射赋值），
因此两条 RAG V2/`strategyExecutor` 分支为不可达死代码；本任务删除该字段、分支及 `QueryRouter` 字段。
生产行为仍是原有的 Dense 直连语义，`effectiveQuery` 仍是会话消歧后的检索查询。

## 3. 启动失败语义

门面与四个调用方全部使用构造器注入，不存在 `@Autowired(required = false)`、`ObjectProvider`
或静默降级路径。任一依赖 bean 缺失时 Spring 上下文启动失败，而不是在请求路径上悄悄退回旧检索。
`KnowledgeSearchAgentTool` 同时移除了两个便利构造器与全部可空注入字段。

## 4. 行为变化

- `TaskPlanContextAssembler`：检索人由"无校验"改为 `plan.createdBy()`，现在会强制项目成员校验，
  非成员失败关闭（抛 `PROJECT_NOT_FOUND`）。这是有意的权限收紧；旧实现绕过成员校验直接检索。
- `SearchProjectKnowledgePlanningTool`：成员校验由门面内部执行；`PROJECT_NOT_FOUND` 仍原样上抛，
  不会被收敛为 `SEARCH_UNAVAILABLE`。其余检索运行时异常（如 embedding 失败）保持既有降级。
- Agent 工具不再存在"pipeline 缺失时走 Dense 直连"的测试便利路径；生产与测试都显式选择模式。
- `documentIds=null` 经门面规范化为空列表；仓储 SQL 对二者语义一致，无行为差异。

## 5. 未改变的部分

- 检索算法、排序、RRF/重排、pgvector SQL、候选池上界常量（20）与引用 JSON 结构。
- `DocumentSearchService` 的中文来源正则与范围解析（保留给 J1 迁移）。
- QA/流式 QA 的提示词、证据装配、拒答路径、Token 预算与时延日志。
- 数据库结构：本任务不新增、不修改任何 Flyway 迁移。

## 6. 测试与验证

- 新增 `ProjectKnowledgeRetrievalTest`（10 项）：DENSE_LEGACY 分发、RAG_V2 路由/过滤/语义查询、
  权限先于检索、topK/query/标识符/模式校验、documentIds 规范化、规范命中透传、范围解析委托与失败关闭。
- 新增测试支撑 `ProjectKnowledgeRetrievalTestSupport`：为仅需旧版 Dense 命中的测试提供
  "legacy-backed pipeline" 适配器，并注入只保留原始查询、不提取 MetadataFilter 的
  passthrough `QueryRouter`；需要真实 RAG V2 契约的测试直接传入真实/专用管线。
  这样迁移前的 4 参工具（无 QueryRouter、Dense 直接使用原始 query）语义被完整保留，
  含《文档名》/排除来源等结构化片段的 P8/P9 冻结问题输入不会被改写。
- 已更新全部 `KnowledgeSearchAgentTool` 构造点、QA/流式构造点，以及
  `RagV2QualityEvaluationPostgresTest` 的 V1 Agent 测量（改为门面 DENSE_LEGACY）。
- 编译：`bash ./mvnw -B -ntp -o -DskipTests compile` 与 `test-compile` 均 BUILD SUCCESS。
- 单元测试（无 Docker）：
  `ProjectKnowledgeRetrievalTest, AgentToolContractTest, AgentReadToolsTest,
  KnowledgeQuestionChatToggleTest, RetrievalPipelineContractFilterPassThroughTest` → 35 tests, 0 failures。
- Testcontainers/Docker 测试仅编译、未执行，标记 NOT_VERIFIED：
  `RagV2QualityEvaluationPostgresTest`、`P9RagGeneralizationPostgresTest`、
  `P8AgentRagCrossLayerPostgresTest`、`RealAgentLoopPostgresTest`、
  `AgentResearchQualityPostgresTest`、`AgentDelegationPostgresTest`、
  `AgentSessionAggregateBudgetPostgresTest`。
- 其中 P8/P9/Agent 运行时测试通过 `legacy-backed pipeline` + passthrough `QueryRouter`
  保持既有"原始查询 + 空过滤"的 Dense 语义，避免改写其冻结基线；真实 RAG V2 管线仅在
  `RagV2QualityEvaluationPostgresTest` 的 V2 工具路径与门面单测中装配。
- 本报告初稿曾把 legacy 适配器描述为"保持既有 Dense 语义"，但当时仍使用生产
  `QueryRouter`；对抗审查发现含《文档名》的查询会被改写成去片段后的 semanticQuery，
  与迁移前输入不一致。现已改为 passthrough 路由并新增回归测试
  `ProjectKnowledgeRetrievalTestSupportTest` 钉住原始查询。

## 7. 回滚

本任务无数据库迁移。回滚方式：整体 revert 本次提交即可恢复到迁移前状态；
门面为新增类，删除后原有 `DocumentSearchService` 依赖仍在，无需数据修复。
