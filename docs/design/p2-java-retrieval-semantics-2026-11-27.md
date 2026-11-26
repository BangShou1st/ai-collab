# P2 Java 现行检索语义报告（供 Python 移植）

> 日期：2026-11-27　作者：P2-J　代码基线：`ai-service-split @ 117a7465`
> 依据：`docs/design/p2-rag-contract-2026-11-27.md` §4.4、§5.1、§9.3；中心 agent 裁决第 6 条。
> 只描述现状，不改代码。行号以基线为准。

## 1. `RetrievalStrategy` 取值与执行语义

来源：`retrieval/query/RetrievalStrategy.java`、`StrategyAwareRetrievalExecutor.java`、`QueryRouteDecision.java:48-55`、`QueryRouter.java:140-195`、`RetrievalPipeline.java`。

共 4 个取值。`decision` 或 `decision.strategy()` 为 null 时按 `HYBRID` 执行。

| 取值 | 何时产生（QueryRouter） | 执行语义 |
|---|---|---|
| `DENSE` | 当前路由**不会产生**（只在枚举和执行器里存在） | 仅 Dense：`denseTopK=20`，`finalTopN=topK`，不走词法，不做句子窗口和父子扩展，重排为 `PASS_THROUGH`（保持 dense 相似度原值和顺序） |
| `HYBRID` | 有结构化元数据过滤（STRUCTURED）；包含精确标识符（错误码、版本号、文件名、全大写代号）；空查询或路由异常时的兜底 | 完整管线：`dense/lexical/rrf TopK = max(20, 2*topK)`，`finalTopN=topK`，句子窗口（size=1）+ 父子扩展，重排为 `LEXICAL_HEURISTIC` |
| `MULTI_QUERY_HYBRID` | 复杂问题（COMPLEX：多问、比较、多意图），子查询来自 `MultiQueryDecomposer` 或 QueryPlan | 子查询数 ≤ 1 时等价于 `HYBRID`。否则查询列表 = `[effectiveQuery] + subQueries`，去掉空白项并去重；每条查询各自走完整 `HYBRID` 管线（`retrieval.multi-query.rerank-primary-only` 默认 false，因此子查询也做启发式重排）。合并方式：主查询结果按原顺序放在前面；只被子查询召回的候选按"各查询 RRF 分之和"降序追加，平分时按 chunk id 升序；截断到 `min(topK + 3*(n-1), 24)`。主查询无召回时，全部候选按 RRF 之和全局排序 |
| `DENSE_WITH_BOUNDED_FALLBACK` | 普通单一意图问题（复杂度 HYBRID 和 FAST 默认都映射到这里） | 先执行 `DENSE`。探针判定"有可用证据"就直接返回；否则**最多补一次** `HYBRID`，补完再调用一次探针（保证最后一次探针调用对应最终返回的列表，结果不影响返回），然后返回 `HYBRID` 的结果。回退原因：`DENSE_EMPTY` / `EVIDENCE_INSUFFICIENT_AFTER_ASSEMBLY` |

所有策略共有的行为：
- 查询为 null 或空白 → 返回空列表。
- **执行器级回退**：策略执行抛出任何 `RuntimeException`，都改走一次 `HYBRID`；如果是 `DENSE_WITH_BOUNDED_FALLBACK`，再对结果补一次探针调用。见 §4 问题 1。
- **管线级静默降级**：Dense 异常 → 候选为空，继续走词法；词法异常 → 为空；重排抛出 `Throwable` → 按融合顺序截断，标记 `degraded`。见 §4 问题 1。
- `effectiveQuery`：如果 `structuredQuery.semanticQuery()` 不为空，就用它（去掉元数据过滤词之后的语义查询），否则用原始问题。
- 文档范围：QueryPlan 给出的 `documentIds` 只能在调用方范围内收窄（`narrowDocumentScope`）。P2 起由 Java 求交后作为 `visibleDocumentIds` 下发。

**RRF / 启发式重排的平局规则**（裁决第 5 条要改成按 `(documentId, chunkNo)` 升序，本文档提交时尚未改）：
- `ReciprocalRankFusion.java:95-107`：RRF 分降序 → 命中路数降序 → 最好名次升序 → `chunk id` 升序；
- `SemanticHeuristicReranker.java:81-93`：rerankScore 降序 → rrfScore 降序（ε）→ `chunk id` 升序；
- `RetrievalPipeline.executeMultiQuery` 中 extras 的排序：RRF 之和降序 → `chunk id` 升序。这一处裁决里没有点名，建议一并改，以免 Python 与 Java 不一致。

## 2. `EvidenceSufficiencyProbe` 能否移植：**能，只依赖候选的文本、分数和元数据**

现有实现只有两处，逻辑相同：`KnowledgeQuestionApplicationService.hasUsableEvidence:203`、`KnowledgeStreamQuestionService.hasUsableEvidence:336`。判定条件是 `!assembleEvidenceContext(candidates).sources().isEmpty()`。装配用的是 `KnowledgeContextBuilder.buildWithTokenBudget(candidates, 5, 2500, tokenCounter, null, null)`（没有 TokenCounter 时用 `buildBalanced`）。

不访问数据库，也不读业务数据。逐条分析装配逻辑后，"sources 不为空"恰好等价于下面这个纯函数：

```
存在一条候选 h，满足：
  1. h 没有被"句子块抑制"剔除：如果候选列表里有 SENTENCE 块，它的 metadata.parentChunkId 指向的那条候选（容器块）会被剔除；
  2. h.similarity（= scores.final，准入分）>= 0.15；
  3. h.content 不为空白。
```

为什么可以化简：
- 每文档名额上限（2）只调整顺序，不删候选；
- 同文档去重只会剔除"已经保留过的那条之后"的重复项；
- token 预算判断是 `usedTokens + blockTokens > maxTokens && !sources.isEmpty()`，**第一条来源永远不会因为预算被丢掉**；
- `buildBalanced` 路径的前缀预算是 8000 码点，文件名 ≤180、标题 ≤300，第一条也不会被前缀预算挡掉。

因此，文件名（业务数据）和 token 计数都不影响探针结论。

移植要点：
- 句子块抑制在 Java 里按 `hit.id()` 与 `metadata.parentChunkId`（UUID）匹配。Python 要改成按 `(documentId, parentChunkNo)` 与 `(documentId, chunkNo)` 匹配，用 §4.3 解析出的 `parentChunkNo`。
- 按 `intent` 选择：`KNOWLEDGE_QA` 用上面这个探针；`AGENT_RESEARCH`、`PLANNING_SOURCE`、`PLANNING_TOOL_LOOKUP` 的调用方（`KnowledgeSearchAgentTool:116`、`TaskPlanContextAssembler:101`、`SearchProjectKnowledgePlanningTool:73`）**不传探针**，回退判据是"候选为空"（`!hits.isEmpty()`）。
- 阈值 0.15 来自 `KnowledgeContextBuilder.MIN_SIMILARITY`，与裁决第 2 条"装配阈值只读 final"一致。

## 3. 向量化输入的现行规则

来源：`DocumentProcessingService:80-82`、`DenseRetriever:41-43`、`ProjectEmbeddingGateway`、`SystemEmbeddingService:193-215`。

**文档侧（`EmbeddingTaskType.DOCUMENT`）**
- 输入就是 `DocumentChunk.content()` 原文，逐块一条：**不拼接 heading，不截断，不加任何指令前缀**；PARENT 块也会被向量化（Java 现状，契约 §5.1 改为 PARENT 不向量化）。
- 输入顺序与 `chunks` 顺序一致，响应必须一一对齐。

**查询侧（`EmbeddingTaskType.QUERY`）**
- 输入为 `effectiveQuery.trim()`，单条，同样不加前缀；
- 多查询策略下，每条子查询各自 trim 后单独向量化。

**dimensions**
- OpenAI 兼容协议：只有 `dimensions > 0` **且 provider 不是 `OLLAMA`** 时，请求体才带 `dimensions`；
- Gemini 原生协议：`dimensions > 0` 时，每条请求带 `outputDimensionality`；
- 配置的 `dimensions == 0` 时，以第一条返回向量的长度为准。所有向量长度必须一致，取值在 1–4096，数值有限。

**batchSize**
- 系统配置范围 1–256；
- OpenAI 兼容协议按 `batchSize` 分批；
- Gemini 按 `min(batchSize<=0 ? 32 : batchSize, 100)` 分批（`batchEmbedContents` 单次上限 100 条）。

**Gemini 原生协议**
- 端点：`{baseUrl+apiPath}/models/{model}:batchEmbedContents`（apiPath 一般为 `/v1beta`）；
- 请求头 `x-goog-api-key`，不用 Bearer；
- 每条请求为 `{model:"models/{model}", content:{parts:[{text}]}, taskType, outputDimensionality?}`；
- `taskType`：文档侧 `RETRIEVAL_DOCUMENT`，查询侧 `RETRIEVAL_QUERY`；`text` 为 null 时传 `""`；
- 响应的 `embeddings[]` 按顺序对齐，没有 index 字段。

**OpenAI 兼容协议**
- `POST baseUrl+apiPath`，请求体 `{model, input:[...], dimensions?}`；`apiKey` 非空时加 `Authorization: Bearer`；
- `data[].index` 全部缺失时按响应顺序对齐，部分缺失视为不合规；有 index 时必须恰好是 `0..n-1`。

**凭据与重试**
- 非 OLLAMA 必须有 key；
- 401/403 → 凭据无效；429 → 配额；400 且响应体包含 `API key not valid` → 凭据无效；
- 5xx 和连接错误最多尝试 3 次，退避 250ms、500ms；其他错误直接失败。

## 4. 与契约对不上的地方（请中心 agent 裁决）

1. **进程内回退**：执行器"策略异常后重跑 HYBRID"、管线里 Dense/词法异常后静默变成空结果、重排异常后降级截断，这三处都是静默回退。契约 §7 和 D4 只点名了 configured reranker 的回退。Python 移植时是保留这些行为，还是改成直接失败？建议：Dense 失败返回 503，不再静默降级为纯词法。
2. **候选池大小**：Java 现行是 `max(20, 2*topK)`，契约 §4.4 的请求带 `candidatePool ∈ [topK, 50]`。建议 Java 下发 `candidatePool = max(20, 2*topK)`，由 Python 用它作为 dense/lexical/rrf 的 TopK；Dense-only 时固定为 20。需要确认。
3. **MULTI_QUERY 的子查询 TopK**：每条子查询都按同一个 `topK` 执行，融合后截断到 `min(topK+3(n-1), 24)`。这个值可能超过契约 §4.4 的 `topK ≤ 20`。返回条数的上限以哪一个为准，需要确认。
4. **`DENSE` 取值当前不会被路由产生**，但 Python 仍需支持（契约要求一一对应）。
