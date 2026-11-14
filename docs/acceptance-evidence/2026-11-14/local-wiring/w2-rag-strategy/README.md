# W2 RAG 策略路由与 QueryPlan 接线

- Lane：`wiring/rag-strategy-queryplan`
- Worktree：`E:\project\ai-collab-wt\wiring-rag-strategy`
- 基线：`1f95447e1880132e34fca2ef57345ccbf7699f5e`
- 状态：`IMPLEMENTED`、`TESTED(UNIT)`

## 实现事实

- `retrieval.qa-mode` 新增配置绑定，允许 `DENSE_LEGACY` / `STRATEGY_AWARE`，代码默认 `DENSE_LEGACY`；非法值在配置绑定阶段失败。
- `ProjectKnowledgeRetrieval.Mode` 新增 `STRATEGY_AWARE`；问答与流式问答按配置选择模式，默认仍走原 `DENSE_LEGACY` 单参检索路径。
- `STRATEGY_AWARE` 通过 `QueryRouter` 产出策略，委托 `StrategyAwareRetrievalExecutor`；普通单意图策略为 `DENSE_WITH_BOUNDED_FALLBACK`，证据探针由两条 QA 链路共享。
- `QueryRouter` 可选使用 `QueryPlanGatewayClient`：`query.plan.enabled=false` 时零外呼；仅 COMPLEX 查询外呼；`confidence >= 0.5` 才采用 rewrittenQuery / subQueries / 可表达的过滤条件；低置信度、超时、网络异常、schema 异常均回退本地规则，并记录 `rag.query_plan.outcome`。
- QueryPlan 返回的 `createdAt` 日期区间映射为现有 `MetadataFilter`；`documentIds` 只允许在调用方范围内收窄；`docTypes` 在当前文档元数据中没有对应字段，未采用不可验证的字段映射，结构化日志记录忽略数量。
- 未修改 `RetrievalPipeline`、Flyway 迁移与仓库默认开关。

## 验证命令

```powershell
.\mvnw.cmd -B -ntp -DskipTests compile
.\mvnw.cmd -B -ntp "-Dtest=com.shitulelv.aicollab.retrieval.application.RetrievalQaPropertiesTest,com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrievalTest,com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrievalStrategyAwareTest,com.shitulelv.aicollab.retrieval.query.QueryRouterTest,com.shitulelv.aicollab.retrieval.query.QueryRouterStrategyMappingTest,com.shitulelv.aicollab.retrieval.query.QueryRouterQueryPlanTest,com.shitulelv.aicollab.retrieval.query.StrategyAwareRetrievalExecutorEvidenceFallbackTest,com.shitulelv.aicollab.retrieval.queryplan.QueryPlanGatewayClientTest,com.shitulelv.aicollab.knowledge.application.service.KnowledgeQaStrategyAwareModeTest,com.shitulelv.aicollab.knowledge.application.service.KnowledgePromptSingleSourceTest,com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionChatToggleTest,com.shitulelv.aicollab.common.ai.prompt.J2PromptPackFailFastTest,com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipelineContractFilterPassThroughTest" test
```

## 原始输出

- `raw/compile.txt`
- `raw/targeted-tests.txt`

定向测试共 73 项，`Failures: 0, Errors: 0, Skipped: 0`。`BundledPromptCatalog` 在既有 `J2PromptPackFailFastTest` 中按设计输出缺 manifest 日志，对应用例仍执行通过。

## 未完成项

- `TESTED(INTEGRATION)`：`NOT_RUN`（Lane 仅执行定向单测，集成栈留待 Wave 2）。
- `TESTED(REAL_PROCESS)`：`NOT_RUN`。
