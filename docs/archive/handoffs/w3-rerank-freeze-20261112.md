# W3 / J3 + P1 施工冻结（rerank）

> 日期：2026-11-12　基线：`dev @ 30bf325d`（含 J2 调试用例删除）
> 设计依据：`docs/design/python-ai-capability-plane-2026-11-11.md` §1.4 / §4 / §5.1 / §5.2 /
> §5.3 / §6.3 / §7.1 / §7.3 / §7.8 / §9 / §10
> 本文件是 S1–S4 的**唯一契约来源**；与代码冲突时以本文件为准并当场记录偏差。

## 0. Phase 0 取证结论（决定接入点）

| 事实 | 证据 |
| --- | --- |
| 生产默认重排器是 `SemanticHeuristicReranker`（`LEXICAL_HEURISTIC`），不是神经重排 | `RetrievalPipeline.PipelineConfig.defaultConfig()`；全仓 `NEURAL_MODEL` 只在注册表出现，无任何生产调用方把它选进 `PipelineConfig` |
| `NeuralModelReranker` 即使被选中也无法调用模型 | 其构造 `ChatCompletionCommand` 时 `callerUserId=null`，`RoutingChatModelGateway.resolveProvider` 直接抛 `AI_PROVIDER_UNAVAILABLE`（`:169-172`），必然降级 |
| Java 侧没有任何内部模型网关 HTTP 端点 | `src/main/java` 无 `/internal/ai/**` 控制器；Python 无法凭"无密钥"身份打分 |
| 现有重排无指标，只有 `log.info/warn` | `NeuralModelReranker` / `RetrievalPipeline` 全文 |

**结论**：J3 的 Java 基线 = 生产实际使用的启发式排序。`rerank.mode` 必须作用在
**检索管线层**（对当前 active reranker 做模式包装），而不是只改 `NeuralModelReranker`；
后者作为 Java 旧实现保留，等 python 门禁通过后再删（本波不删）。

## 1. Python `POST /v1/rag/rerank`（冻结）

请求（`extra=forbid`，camelCase）：

```json
{"schemaVersion":1,"query":"…","topK":8,
 "candidates":[{"id":"c1","title":"…","text":"…"}],
 "deadlineMs":3000}
```

| 字段 | 规则 |
| --- | --- |
| `schemaVersion` | 必须 1 |
| `query` | 1–4000 字符，去首尾空白后非空 |
| `topK` | 1–50，默认 8 |
| `candidates` | 1–50 条；`id` 1–128 字符且**请求内唯一**；`title` 可空、≤500；`text` 1–2000 字符 |
| `deadlineMs` | 1–30000，默认 3000；服务端硬 deadline |

响应：

```json
{"schemaVersion":1,"scores":[{"id":"c1","score":0.83}],
 "modelAlias":"rerank-default",
 "usage":{"inputTokens":0,"outputTokens":0,"source":"PROVIDER"}}
```

- `scores` **必须覆盖全部输入 id**、按输入顺序返回、`score ∈ [0,1]`；
- `usage.source ∈ PROVIDER | ESTIMATE | UNKNOWN`，缺 usage 标 `UNKNOWN`，不填 0 冒充；
- `modelAlias` 来自 worker 配置（默认 `rerank-default`）。
- 错误码只用 P0 已冻结的 9 个：`INVALID_REQUEST`(400/422)、`DEADLINE_EXCEEDED`(504)、
  `MODEL_UNAVAILABLE`(503)、`MODEL_RATE_LIMITED`(429)、`SCHEMA_VIOLATION`(422)、`INTERNAL`(500)。
- 超时**不得返回半成品**：deadline 到点即 `DEADLINE_EXCEEDED`，无 scores 字段。
- `Idempotency-Key`（可选，8–128 位 `[A-Za-z0-9._:-]`）：同 key + 同请求体 → 10 分钟内
  返回**逐字节相同**的响应且不重复调用模型；同 key + 不同请求体 → `INVALID_REQUEST`(409 语义
  用 400)；缓存有界（≤1024 条）。
- `text` 超过 2000 字符或 candidates >50 → `INVALID_REQUEST`，不静默截断。
  提示词内截断（如 400 字符/条）属于打分策略，不影响契约。

## 2. Python → Java 内部模型网关（冻结）

`POST /internal/ai/v1/model-calls`（Java 提供；本波新增）

请求：

```json
{"schemaVersion":1,"purpose":"KNOWLEDGE_CHAT","systemPrompt":"…","userPrompt":"…",
 "outputFormat":"JSON_OBJECT","deadlineMs":2500,"callId":"…"}
```

响应：

```json
{"schemaVersion":1,"content":"…","usage":{"inputTokens":1,"outputTokens":2,"source":"PROVIDER"},
 "modelAlias":"…","latencyMs":812}
```

- 鉴权：`Authorization: Bearer <内部服务令牌>`；令牌来自 Java 配置
  `rerank.service-token` 与 worker 环境 `AI_WORKER_SERVICE_TOKEN`，**只经环境变量传递，
  不入库、不打印、不写文档**。未配置令牌时 Java 端点拒绝所有请求（fail closed），
  worker 在未配置令牌时也拒绝执行（除非显式 `AI_WORKER_ALLOW_INSECURE_INTERNAL=true`，
  仅测试用）。
- 失败：模型不可用→`MODEL_UNAVAILABLE`(503)、限流→`MODEL_RATE_LIMITED`(429)、
  超时→`DEADLINE_EXCEEDED`(504)；错误信封与 §7.1 一致。
- **服务账号**：Java 以 `rerank.model-user-id` 配置的服务账号调用 `ChatModelGateway`
  （现有网关必须非空 `callerUserId`）；未配置→`MODEL_UNAVAILABLE`。这是本波对
  "预算/账本归属"的临时落实，登记为遗留（W4 换系统共享模型档案）。
- 本端点只允许内网；nginx 不代理 `/internal/**`。

## 3. Java `rerank.mode`（冻结）

| 值 | 行为 |
| --- | --- |
| `java`（默认） | 完全委托当前 active reranker，**逐分数/逐顺序等价**；不产生任何 Python 调用 |
| `shadow` | 并行计算 Java 结果与 Python 结果；**返回 Java 结果**；只记录对比指标；Python 失败不影响返回 |
| `python` | 使用 Python 分数；失败/超时/非法响应/缺 id → Java 启发式分数（缺失 id 用启发式逐条补），标 `PARTIAL_SCORE`；请求不失败 |

- 仅对 `rerankerType != PASS_THROUGH` 生效（Dense-only 的"不重排"语义不得被改写）。
- 候选为空/查询为空：直接返回，不调用 Python。
- `RerankGatewayClient`：`connect 2s` / `read = deadlineMs + 5s`；只对**连接失败/502/503/504**
  重试 ≤2 次（指数退避 100/200ms）；4xx/超时/非法 JSON 不重试。
- 请求头：`Idempotency-Key`（`sha256(projectId|query|topK|candidateIds)` 前 32 hex，
  重试不变）、`X-Call-Id`（`rerank:<projectId>:<hash8>:<attempt>`）、`traceparent` 透传（有则带）。
- 响应校验：`schemaVersion==1`、scores 覆盖候选集（缺 id 记 `PARTIAL_SCORE`）、
  分数有限且 ∈[0,1]；任一非法→按失败处理（python 模式回退 / shadow 只记失败）。
- 证据不可变：只调用 `withRerank`/`withMetadata`，不得改 `id/documentId/heading/content/
  contentHash/metadata` 原有键。
- 指标（Micrometer，低基数）：`rerank.calls{mode,outcome}`、`rerank.latency{mode}`
  （P50/P95 由 histogram 提供）、`rerank.fallback{reason}`、`rerank.partial_score`、
  `rerank.shadow.topk_overlap`、`rerank.shadow.score_delta`。

## 4. 评测口径（冻结）

冻结集：`ai-collab-ai-worker/tests/fixtures/rerank_set/rerank_baseline.v1.jsonl`
（15 条，中文；每条含 `id/query/candidates[{id,title,text}]/labels{id:0|1|2}/supports[id]`；
全部为合成语料，**不含任何生产数据**）。

| 指标 | 口径 |
| --- | --- |
| NDCG@8 | graded gain `2^rel-1`，discount `log2(rank+1)`，IDCG 取理想排序；逐 case 平均 |
| 引用支持率 | top-8 中 `supports[id]=true` 的条数 / min(8, 候选数)，逐 case 平均 |
| P95 | 每条 case 连续执行 20 次（warmup 2 次不计）的端到端耗时，取全体样本 P95（毫秒） |

- `ai-eval rerank` 两种 scorer：`heuristic`（Python 复刻 Java `SemanticHeuristicReranker`
  公式，作为 Java 基线代理，Java 侧有同集一致性测试）与 `worker`（HTTP 打真实 Python 端点，
  走真实模型）。
- 输出 canonical JSON + SHA-256（复用 P0 `eval/canonical.py`），同输入两次逐字节一致。
- 真实模型未接入时，`worker` 结果标 `NOT_VERIFIED`；staging shadow 的 top-8 重合率与
  分数差另记，不冒充 NDCG。

## 5. 明确排除

query plan（P2）、decide（P2）、planning/memory（P3）、MCP（P4）；不改前端；不改历史迁移；
不新增业务表；不删除 `NeuralModelReranker`；不动 J1-1/J1-2 的删除项。

## 6. 文件所有权（避免并行冲突）

| 路 | 独占文件 |
| --- | --- |
| S1 | `ai-collab-ai-worker/src/ai_collab_worker/{api/rerank.py,contract/rerank.py,contract/snapshots/v1/rerank-*.json,cognition/rerank/**,clients/java_gateway.py,security/**,config.py,main.py,observability/metrics.py,contract/schema.py,api/__init__.py}`、`pyproject.toml`/`uv.lock`、`tests/{unit,contract,integration}/**rerank**` |
| S2 | `ai-collab-ai-worker/src/ai_collab_worker/eval/rerank.py`、`eval/cli.py`、`tests/fixtures/rerank_set/**`、`tests/unit/test_rerank_eval.py`、`tests/integration/test_rerank_eval_cli.py` |
| S3 | `ai-collab-backend/src/main/java/com/shitulelv/aicollab/retrieval/rerank/**`、`.../internal/ai/**`、`RetrievalPipeline.java`（仅 3 行）、`SecurityConfig.java`、`application.yml`、`ai-collab-deploy/docker-compose.yml` |
| S4 | `ai-collab-backend/src/test/java/**` 新增测试；不改生产代码 |
| S5 | 文档与报告（集成后由编排 Agent 执行） |
