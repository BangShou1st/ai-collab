# P2 RAG 迁到 Python：Java ↔ Python 接口契约（冻结 v1）

> **状态**：FROZEN（v1）。第 11 节的 14 个问题已由中心 agent 于 2026-11-27 代用户拍板，Java / Python 两个工作包都以本文为准。
> **修订**：P2-R1（2026-11-27，§12.3）：迁移不用 Alembic、`scores.final` 定为准入分、系统级 EMBEDDING 租借、EMBEDDING lease 参数与协议、平局规则、策略执行待 Java 报告。
> **修订**：P2-R3（2026-11-27，§12.5）：引用复核调用点挪到切换工作包；缺 `document_body` 的存量文档切换前补正文；§7 部署前置检查增加第 5 条。
> **修订**：P2-R4（2026-11-27，§12.6）：`REINDEX_GENERATION` 的 `project_id` 可为空（改 V105）；孤儿索引行由 Java 补偿扫描经 `:reconcile` 兜底清理（Python 侧待办）。
> **日期**：2026-11-27
> **上位设计**：[ai-service-split-and-redis-2026-11-25.md](ai-service-split-and-redis-2026-11-25.md) §2、§4、§5、§6、§13 P2、§14.6、§15 E1/E2、§18。本文对它的修订集中写在 §12。
> **沿用契约**：[p1-ai-kernel-contract-2026-11-26.md](p1-ai-kernel-contract-2026-11-26.md)（含 R1–R3）。令牌、scope 集合语义、epoch、claim/heartbeat/result/fail、错误体、日志脱敏一律按 P1 执行，本文只写增量；对 P1 的修订同样写在 §12。
> **代码基线**：`ai-service-split @ 57583ba4`，Flyway 已到 `V104`，P2 的 Java 迁移从 **`V105`** 开始。
> **编制方式**：只读调研，没有启动 Docker、Maven 或 pytest。文中"现状"都标了出处；没在仓库里找到的写"未找到"。
> 本文与上位设计冲突时，以本文 §12 列出的修订为准；其余冲突回报中心 agent，实现方不得自行分叉。

---

## 0. P2 范围

| 做 | 不做 |
|---|---|
| Python 独占 `ai_index`：建表、HNSW、向量化、写入、删除、重建索引 | 切块（仍由 Java 的 `DocumentChunker` 完成，见 §12 D1） |
| Python 检索：查询向量化、策略执行（子查询检索与融合、证据充分性判断、有界回退）、Dense、词法、RRF、rerank、句子窗口与父子扩展 | 查询理解：`QueryRouter`、多查询分解、元数据过滤解析、`/v1/rag/plan`（随 P3 迁，见 §12 D2） |
| Java 负责：解析、切块、可见性计算、引用复核；Java 的四个检索调用方切到 Python 检索接口（§4.4） | 知识问答生成和流式输出（P3）、Agent 图（P4）、规划生成（P5） |
| 删除 Java 检索执行、重排、向量化写入路径（§8） | 删除 `document_chunk` 等旧表（按设计 §5.4，放到 P7） |
| 39 题评测和 DuRetrieval 基准改写成 Python（E1、E2） | Java 侧模型适配器的整体删除（P7） |

## 1. 现状盘点（只读调研结果）

### 1.1 写入链路（Java）

| 步骤 | 类 / 表 | 出处 |
|---|---|---|
| 注册 | RocketMQ 事务消息 `DocumentRegistrationTransactionListener` → `DocumentProcessingMqConsumer` / `DocumentProcessingMqListener` → `DocumentProcessingService` | `document/infrastructure/mq/`、`document/application/service/` |
| 解析 | `TikaDocumentParser` → `ParsedDocument`（含 `PageBoundary`） | `document/infrastructure/parser/` |
| 切块 | `DocumentChunker`：`TARGET=1000`、`OVERLAP=120`、`MAX_CHUNKS=1000`，按 Markdown 标题、法规条款和推断标题分节；短节→`STANDARD`，长节→`PARENT` 加若干 `CHILD`；可选 `SENTENCE` 句子块；metadata 里有 `charFrom/charThrough/offsetCoordinate=CLEANED_TEXT_UTF16_V2/pageNumber/parentChunkId/chunkType/isTable/isCode`，CHILD 还有 `parentCharFrom/parentCharThrough`；`content_hash = sha256(content)` | `document/domain/service/DocumentChunker.java` |
| 正文落库 | `DocumentContentService.saveParsed` 写 `document_body`（`snapshot_id`、`original_content_hash`、`parse_version=tika-page-clean-v2/chunk-offset-v2`）和 `document_body_chunk`（**切块结果和 `document_chunk` 来自同一个 `DocumentChunker`**，只是没有向量）。注意：写入时行 id 用的是 `UUID.randomUUID()`，不是切块器生成的 id，所以 metadata 里的 `parentChunkId` **对不上任何已落库的行**（见 §4.3） | `DocumentContentService.java:38-45`，V54 |
| 向量化 | `ProjectEmbeddingGateway` → `OpenAiCompatibleEmbeddingGateway`，`EmbeddingTaskType.QUERY/DOCUMENT`；指纹 `EmbeddingFingerprints.fingerprint(provider, model, dimension)` | `infrastructure/ai/embedding/`、`document/infrastructure/ai/` |
| 写块 | `DocumentIndexWriter.replaceAndComplete`：锁 `system_embedding_config`，校验 active 指纹与 generation，`DocumentRepository.replaceChunks` 替换块，`markReady` 置 `READY`，写审计 `DOCUMENT_INDEXED` | `DocumentIndexWriter.java` |
| 重建 | `BatchReindexService`、`EmbeddingIndexService`、`SystemEmbeddingAdminController`；表 `embedding_index_generation`（**全站级**，`state ∈ QUEUED/BUILDING/READY/ACTIVE/RETIRED/FAILED`，同时只允许一个在建）、`embedding_index_document` | V46、V48 |

### 1.2 表

| 表 | 关键字段 | 出处 |
|---|---|---|
| `document_chunk` | `embedding vector NOT NULL`（**不定长**）、`embedding_provider/model/dimension/fingerprint`、`generation_id`、`parent_chunk_id`、`chunk_type ∈ STANDARD/PARENT/CHILD/SENTENCE`、`metadata jsonb` | V1、V43、V46、V78、V80 |
| `document_body` / `document_body_chunk` | 正文快照与正文块：`(document_id, chunk_no)` 唯一，有 `heading/content/content_hash/metadata`，**没有 `chunk_type`、`parent_chunk_id` 列**（只在 metadata 里），没有向量 | V54 |
| `knowledge_citation` | `chunk_id uuid NOT NULL REFERENCES document_chunk(id) ON DELETE CASCADE` | V1:206-214 |
| `connector_document_visibility`（视图） | 外部连接器文档的可读性 | V70:129 |

### 1.3 检索链路（Java）

- 统一门面 `retrieval/application/ProjectKnowledgeRetrieval`：先 `requireMember`，再按 `RetrievalIntent` 走 `QueryRouter` + `StrategyAwareRetrievalExecutor`；`topK ∈ [1, 20]`；还留着 `retrieval.force-legacy-dense` 和 `Mode.DENSE_LEGACY` 应急开关。
- 四个调用方：
  - `KnowledgeQuestionApplicationService:111` 和 `KnowledgeStreamQuestionService:139`（`KNOWLEDGE_QA`）
  - `KnowledgeSearchAgentTool:118`（`AGENT_RESEARCH`，候选池 20）
  - `TaskPlanContextAssembler:105`（`PLANNING_SOURCE`）
  - `SearchProjectKnowledgePlanningTool:75`（`PLANNING_TOOL_LOOKUP`）
- 查询理解：`QueryRouter`、`QueryUnderstandingService`、`MultiQueryDecomposer`、`MetadataFilterParser`、`EvidenceSufficiencyProbe`；复杂查询可选调用 Python `/v1/rag/plan`（`QueryPlanGatewayClient`）。
- 管线：`RetrievalPipeline`，由 `DenseRetriever`、`LexicalRetriever`、`ReciprocalRankFusion`（`DEFAULT_K = 60`）、`SentenceWindowExpander`、`ParentChildExpander`、`PostgresDocumentSectionReader`（从 `document_body_chunk` 回读章节）和 Reranker 组成。
- Dense SQL（`DocumentMapper.java:224-289`）：`1 - (c.embedding <=> CAST(:q AS vector)) AS similarity`，即**余弦距离**。过滤条件：
  - 项目、`d.status='READY'`、`NOT d.access_blocked`；
  - 外部连接器文档必须 `e/s/a` 三者都是 ACTIVE 且授权没过期；
  - provider/model/dimension/fingerprint/generation 全部一致；
  - 排除 `PARENT` 块；
  - 可选 `documentIds`；
  - 可选 `DocumentFilterCriteria`（文件名、标题、创建和更新时间、状态）。
- 词法：pg_trgm 的 `%` 运算符走 GIN（`idx_document_chunk_content_trgm`、`idx_document_chunk_heading_trgm`，V78）。阈值通过连接初始化 `SET pg_trgm.similarity_threshold = 0.03` 设置（`application.yml:12`）。
- 引用与证据复核（Java，**都直接读 `document_chunk` 或 `document_body_chunk`**）：
  - `EvidenceVerificationService.fetchVerifiedChunks`（`document_chunk` + READY + 未封锁 + 可读 + 哈希比对）
  - `KnowledgeCitationValidator`、`KnowledgeCitationMapper:42-48`
  - `KnowledgeMessageMapper:23-26`
  - `AgentRepository:1158`（`document_chunk UNION document_body_chunk`）
  - `AgentRunEventRecorder:1979/2007`（`document_body_chunk`）

### 1.4 pgvector 索引、维度、距离、reranker

| 项 | 现状 | 出处 |
|---|---|---|
| 向量索引 | **没有**。迁移里搜不到 `hnsw`/`ivfflat`；2026-10-24 的只读核查也记录"无向量索引" | `docs/acceptance-evidence/2026-10-24/p5-release-readiness-01/evidence-index.md:17` |
| 维度 | 列是不定长 `vector`；评测和默认模型是 `qwen3-embedding:0.6b`，维度 1024 | `RagV2QualityEvaluationPostgresTest.java:101-103` |
| 距离 | 余弦 `<=>`，`similarity = 1 - distance` | `DocumentMapper.java:229` |
| pgvector 版本 | 本地实测 **PostgreSQL 17.11 + pgvector 0.8.6**（2026-10-24）。各 compose 都钉在同一个镜像 `pgvector/pgvector:pg17@sha256:cf134a76…`，但 **staging 上的版本没有在仓库里找到实测记录**（部署前置检查见 §7） | `docs/release-readiness/p5-release-readiness-01/database-and-migration-review.md:9`；`ai-collab-deploy/compose.*.yml` |
| reranker | 默认 `LEXICAL_HEURISTIC`（`SemanticHeuristicReranker`）。只有显式选择 `CONFIGURED_RERANK` 才走 `ConfiguredReranker` → `RerankCapabilityResolver` 解析能力绑定 → `LlamaCppRerankAdapter`（HTTP，`baseUrl + apiPath`，凭据由 `ModelSecretCipher` 解密）。inline 调用最多 8 个候选（`RerankExecutionPolicy`）；**任何失败都静默回退启发式**（P2 删除这条回退，见 §12 D4） | `retrieval/pipeline/ConfiguredReranker.java`、`infrastructure/ai/capability/LlamaCppRerankAdapter.java` |

### 1.5 数据库角色（已有）

`ai-collab-deploy/postgres/db-init.sql` 已经建好：
- `ai_collab_app`：业务 owner，Flyway 用它执行迁移；
- `ai_worker`：拥有 `ai_index` 和 `ai_runtime`，`search_path = ai_index, ai_runtime, public`；
- 撤销了 `ai_collab_app` 和 PUBLIC 对两个 AI schema 的全部权限，也撤销了 `ai_worker` 对 public 下全部表和序列的权限；
- `ai_worker` 只保留 public 的 USAGE，用来解析 vector 类型和运算符。

CI（`.github/workflows/ci.yml:279-285`）已经在跑这份脚本。

### 1.6 Python worker（P1 之后）

已有 `models/factory.py`（预留了 `embedding_model(lease)`）、`models/lease.py`、`tasks/stream.py`、`tasks/memory.py`、`api/query_plan.py`、`security/redaction.py`。**没有数据库依赖，也没有 Alembic**。

**已决定（Q13）**：P2 允许新增一个 PostgreSQL 驱动（`psycopg[binary]>=3` 或 `asyncpg`，二选一，Python 工作包定）、`pgvector`（Python 适配包）和 `alembic`，版本锁进 `uv.lock`；同时把 `tests/unit/test_forbidden_dependencies.py` 的白名单更新为恰好包含这几项，其余禁止项保持不变。

## 2. 评测基线（只引用仓库内报告）

| 口径 | QA 必要事实保留 | Agent 工具 | 空上下文 | 来源 |
|---|---|---|---|---|
| V1 基线（P9 冻结协议，2026-10-26，`3891171e`） | **52/54** | 51/54（limit=12 对照 53/54） | — | `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/README.md:29` |
| V2 生产管线修复后（2026-11-07） | **52/54** | 53/54 | 0/39 | `docs/archive/report/2026-11-07-rag-v2-quality-regression-fix-report.md:86-102` |
| 最近一次实测（2026-11-08，`88e64734`，三轮） | **52/54** | 53/54 | — | `docs/optimization/rag-v2-routing-final-closure/final-report.md:53-61`；`evidence/p9-rerun/rag-v2-quality-eval-results-run3-closure2.json:8348`（`"qaRetained" : "52/54"`） |

- 题集：23 篇冻结语料、175 个 chunk、39 题（dev 21 + holdout 18）、54 条必答事实。清单在 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/{corpus-manifest,question-manifest,question-manifest-holdout,holdout-integrity}.json`。
- 现有跑法：`AI_REAL_OLLAMA_RETRIEVAL=true ./mvnw test -Dtest=P9RagGeneralizationPostgresTest` 跑 V1 协议；`AI_RAG_V2_QUAL_EVAL=true` 跑 `RagV2QualityEvaluationPostgresTest`，即 V2 生产路径。两者都需要本地 Docker 和 Ollama `qwen3-embedding:0.6b`。
- 指标性质：**检索 / 上下文层的字符串匹配保留率，不是回答准确率**；题集是自建合成题集（设计 §15 E2 要求如实标注）。
- **余量为 0**：基线 52/54 正好等于 P2 门槛 52/54，多丢 1 条就触发 §18 停止条件。这是 §12 D1（切块留在 Java）的主要理由之一。
- 自 11-08 以后到 `57583ba4`，仓库里**没有找到**新的 39 题实测记录。

## 3. 职责划分

| Python（`ai_worker` 角色） | Java（`ai_collab_app` 角色） |
|---|---|
| `ai_index` 建表和迁移（Alembic）、HNSW / GIN 索引 | 业务 schema（Flyway），**不读写 `ai_index`** |
| 从 Java 分页拉 `document_body_chunk` 行 → 对非 PARENT 行向量化（凭据租借 `EMBEDDING`）→ 同一事务内替换该文档在目标 generation 下的索引行 | Tika 解析、**切块**（`DocumentChunker`）、`document_body(_chunk)` 落库、文档状态机（`INDEXING → READY/FAILED`）、审计 |
| 删除文档索引；创建 generation、对账、激活 | 发起写入、删除、重建任务；重建的扇出与完成判定（§4.2） |
| 检索：查询向量化、策略执行、Dense、词法、RRF、rerank（启发式移植 + 配置的 reranker，凭据租借 `RERANK`）、句子窗口与父子扩展 | 查询理解（`QueryRouter` 等，P3 前留在 Java）：产出策略、改写后的查询、子查询、文档范围收窄 |
| 只在 `visibleDocumentIds` 范围内检索，返回带身份和哈希的候选 | **可见性计算**：项目成员、READY、`access_blocked`、连接器授权、业务元数据过滤（文件名和日期）→ 得到 `visibleDocumentIds` |
| **不读写**业务 schema | **引用复核**：按 `documentId + chunkNo + contentHash` 对照 `document_body_chunk`（§6）；问答会话、引用落库、提示词装配（P3 前仍在 Java）；rerank 能力配置与凭据 |

硬约束（审查清单）：
1. Python 不能读写业务 schema，Java 不能读写 `ai_index`。用数据库权限保证，并有测试证明（§9.2）。
2. 每一次检索都带 Java 下发的 `visibleDocumentIds`；集合为空直接返回空结果，**不能理解成"全项目"**（注意，现有 Java 门面的 `documentIds` 为空表示全项目，这个语义在 P2 接口里取消）。
3. Java 复核 Python 返回的每条引用；复核不过的条目丢弃并计数，**不能当成已验证的事实**（AGENTS.md §6）。
4. 不保留进程内回落：Python 检索失败时 Java 不回退到 Java 检索；配置的 reranker 失败时 Python 不回退到启发式（§12 D4）。

## 4. 接口清单

### 4.1 令牌与 scope（在 P1 §1 基础上扩展）

| scope | 方向 | 允许访问 | 额外 claim |
|---|---|---|---|
| `task:{taskId}` | Python → Java | 沿用 P1，用于 `INDEX_DOCUMENT`、`DELETE_DOCUMENT_INDEX`、`REINDEX_GENERATION` 任务的 claim/heartbeat/snapshot/result/fail | `tid`、`ep`、`purp=["EMBEDDING"]` |
| `index:document:{docId}` | Python → Java | `GET /internal/ai/documents/{docId}/body`（docId 必须与 scope 一致） | `pid` |
| `knowledge:search` | Java → Python | `POST /v1/knowledge/search` | `uid`、`pid`、`purp=["EMBEDDING","RERANK"]` |
| `index:admin` | Java → Python | `/v1/index/generations/**`（§4.6） | 无 `uid/pid` |
| `credentials:lease` / `usage:report` | Python → Java | 沿用 P1 | |

- 检索入站令牌 `scope` 写法：`knowledge:search credentials:lease usage:report`。Python 校验 `knowledge:search`、`uid`、`pid`，并且令牌里的 `pid/uid` 必须等于请求体里的 `projectId/userId`；其余两个 scope 由 Java 在租借和用量回报时校验（与 P1 §9.6 一致）。
- `INDEX_DOCUMENT` 的任务令牌同时带 `index:document:{docId}`：claim 和 heartbeat 换发的新令牌也要带上。Java 的 `InternalAiTokenFilter.PATH_SCOPES` 按上表登记，不得放宽为"任意 scope"。

### 4.2 写入、删除、重建：走 Redis Stream

理由：这些都是长任务，P1 已经有 Stream + claim + epoch + 补偿扫描框架；HTTP 推送已在 P1 删除，不再引入。消息字段沿用 P1 §9.2：`taskType`、`taskId`、`epoch`、`token`。

| taskType | Java 何时创建 `ai_job` 并在 `AFTER_COMMIT` 后 `XADD` | Python 执行 | result payload |
|---|---|---|---|
| `INDEX_DOCUMENT` | 解析和切块完成、`document_body(_chunk)` 落库、文档进入 `INDEXING` 时；或由重建扇出（带 `generationId`） | 分页拉正文块 → 对非 PARENT 行向量化 → 在目标 generation 下按 `document_id` 事务内删旧插新 | 见下 |
| `DELETE_DOCUMENT_INDEX` | 文档删除流程（`DocumentDeletionService` / `DocumentDeletionPurgeWorker`）、项目删除（每篇文档一个任务） | `DELETE FROM ai_index.chunk WHERE project_id=$1 AND document_id=$2`，覆盖所有 generation；同时删除对应的 `indexed_document` 行 | `{documentId, deletedRows}` |
| `REINDEX_GENERATION` | 管理员发起全量重建（替换 `BatchReindexService`）；embedding 模型变更后也走这里 | **只做一件事**：租借 `EMBEDDING`，按租到的模型创建 `BUILDING` generation | `{generationId, embeddingModel, dimension}` |

**目标 generation 规则（全站只有一个 ACTIVE，最多一个 BUILDING）：**
- `INDEX_DOCUMENT` 快照里没有 `generationId`（常规上传）：写入 ACTIVE generation，**以及**当前 BUILDING generation（如果有），保证重建期间上传的文档不会漏掉；
- 带 `generationId`（重建扇出）：只写这一个 generation；该 generation 已不是 `BUILDING`/`ACTIVE` 时上报 fail(`INDEX_GENERATION_STALE`, retryable=false)；
- 既没有 ACTIVE 也没有 BUILDING（首次切换前）：上报 fail(`INDEX_GENERATION_UNAVAILABLE`, retryable=true)，由补偿扫描重排。

**重建流程（已决定 Q6）：**
1. Java 创建 `REINDEX_GENERATION` 任务；Python 创建 BUILDING generation，在 result 里返回 `generationId`。
2. Java 处理 result 时，在同一事务里为每篇 READY 文档创建一个 `INDEX_DOCUMENT` 子任务（`parent_job_id` 指向重建任务，带 `generationId`），提交后批量 `XADD`。
3. 子任务的失败重试、租约过期、补偿扫描全部沿用 P1 §4.4。
4. Java 补偿扫描发现某个重建任务的子任务全部 `SUCCEEDED` 时：
   - 调用 Python 的 `:reconcile`，把当前 READY 文档集合分批下发，取回缺失集合；有缺失就为缺失文档补派子任务，等下一轮扫描；
   - 没有缺失时调用 `:activate`（§4.6），成功后把重建任务标为完成。

   这两个调用都是幂等的，扫描器重复执行或并发执行都不会激活两次（Redisson 锁兜底，Python 侧还有唯一索引兜底）。
5. 任何子任务进入终态 `FAILED`：Java 调 `:abandon` 把 generation 置 `FAILED`，重建任务置 `FAILED`，ACTIVE generation 不受影响。
6. 可靠性靠 `ai_job` 的数据库状态加补偿扫描，不靠 Stream 消息本身。Java **不**镜像 generation 表（Q5），`generationId` 只作为任务参数记在 `ai_job` 上。

**任务快照** `GET /internal/ai/tasks/{taskId}/snapshot`（沿用 P1 路径）：

```json
{ "schemaVersion": 1, "taskId": "uuid", "taskType": "INDEX_DOCUMENT",
  "projectId": "uuid", "documentId": "uuid", "generationId": "uuid|null",
  "bodySnapshotId": "uuid", "parseVersion": "tika-page-clean-v2/chunk-offset-v2",
  "bodyChunkCount": 175 }
```

`DELETE_DOCUMENT_INDEX` 只带 `projectId/documentId`；`REINDEX_GENERATION` 只带 `taskId/taskType`。

**INDEX_DOCUMENT 的 result payload：**

```json
{ "documentId": "uuid", "bodySnapshotId": "uuid",
  "generations": [ { "generationId": "uuid", "indexedRows": 175, "embeddedRows": 160 } ],
  "embeddingModel": "qwen3-embedding:0.6b", "dimension": 1024 }
```

- Java 校验：
  - `bodySnapshotId == document_body.snapshot_id`；
  - 每个 generation 的 `indexedRows` 等于该文档 `document_body_chunk` 的行数。**0 行是合法的**（Q8：空正文的文档为 READY，0 个切块）。

  校验通过后：常规上传把文档置 `READY`、写审计 `DOCUMENT_INDEXED`；重建子任务只把任务置 `SUCCEEDED`，不改文档状态。
- 快照已变化（文档在索引期间被重新上传或重新解析）：返回 **409 `INDEX_SNAPSHOT_STALE`**，Python 按 P1 规则不重试；新快照会有自己的任务。
- fail 的 `errorCode`：
  - `EMBEDDING_DIMENSION_MISMATCH`
  - `EMBEDDING_MODEL_MISMATCH`（租到的模型与 generation 记录的不一致）
  - `BODY_NOT_FOUND`
  - `INDEX_GENERATION_STALE`
  - `INDEX_GENERATION_UNAVAILABLE`
  - `provider_*`（沿用 P1 的小写 provider 码）

  常规上传任务终态失败时，Java 把文档置 `FAILED`，`error_message` 用固定文案。
- 幂等：Python 对同一 `(generation_id, document_id)` 的写入是在同一事务里删旧插新，重复执行结果相同；Java 侧沿用 P1 §4.3 的终态幂等规则。
- `ai_job.job_type` 的 CHECK 加上这三个值，并增加 `document_id`、`generation_id`、`parent_job_id`（V105，§5.4）。

### 4.3 正文块分页读取（Java 提供）

```
GET /internal/ai/documents/{docId}/body?cursor=<opaque>&limit=<1..200>
scope: index:document:{docId}（INDEX_DOCUMENT 的任务令牌已带）
```

响应 `200`：

```json
{ "documentId": "uuid", "projectId": "uuid", "bodySnapshotId": "uuid",
  "parseVersion": "tika-page-clean-v2/chunk-offset-v2", "totalChunks": 175,
  "chunks": [ {
    "chunkNo": 0, "chunkType": "STANDARD | PARENT | CHILD | SENTENCE",
    "parentChunkNo": null, "heading": "...", "content": "...", "contentHash": "sha256-hex",
    "metadata": { "charFrom": 0, "charThrough": 980, "pageNumber": 1, "isTable": false }
  } ],
  "nextCursor": "opaque|null" }
```

- 按 `chunk_no` 升序直接返回 `document_body_chunk` 行（方案 B，§12 D1），Python 不再切块。`chunkType` 取 `metadata.chunkType`，缺失时为 `STANDARD`。
- **`parentChunkNo` 由 Java 解析。** 原因是 `saveParsed` 把行 id 写成新的随机 UUID，metadata 里的 `parentChunkId` 对不上已落库的行（§1.1）。规则：
  1. 新数据：P2 把 `saveParsed` 改为用切块器生成的 id 作为行 id。这样 `metadata.parentChunkId` 就等于父行 id，直接映射成父行的 `chunk_no`；
  2. 存量数据（行 id 对不上）：CHILD 取 `charFrom/charThrough` 等于本行 `metadata.parentCharFrom/parentCharThrough` 的 PARENT 行；SENTENCE 取 `chunk_no` 小于本行、且字符区间包含本行区间的最近一个 STANDARD 或 CHILD 行；
  3. 两条都匹配不到时为 `null`，Java 记录指标 `document_body_parent_unresolved_total`，**不报错**。这套规则要有单元测试（§9.3）。
- 返回的 `metadata` 只包含白名单字段：`charFrom`、`charThrough`、`pageNumber`、`pageThrough`、`isTable`、`isCode`。
- Java 校验：令牌 `pid` 等于文档所属项目；文档不处于 `DELETING`；没有被封锁。不满足返回 404（不区分原因）。
- 快照在分页过程中变化（`cursor` 里带 `bodySnapshotId`）：返回 **409 `INDEX_SNAPSHOT_STALE`**，Python 放弃本次任务。

### 4.4 检索接口（Python 提供，同步 HTTP，内部接口）

```
POST /v1/knowledge/search
Authorization: Bearer <scope: knowledge:search credentials:lease usage:report>
```

- **已决定（Q3）**：这是正式保留的内部检索接口，P3 的问答、P4 的 Agent 工具、P5 的规划都复用它。前端看不到这个接口，Nginx 不对外暴露 worker。

请求：

```json
{
  "requestId": "uuid",
  "projectId": "uuid",
  "userId": "uuid",
  "intent": "KNOWLEDGE_QA | AGENT_RESEARCH | PLANNING_SOURCE | PLANNING_TOOL_LOOKUP",
  "strategy": "QueryRouter 给出的 RetrievalStrategy 原值",
  "queries": [ { "text": "改写后的主查询", "role": "PRIMARY" },
               { "text": "子查询", "role": "SUB" } ],
  "originalQuery": "用户原始问题",
  "visibleDocumentIds": ["uuid", "..."],
  "topK": 8,
  "candidatePool": 20,
  "headingFilter": { "exact": null, "contains": null },
  "rerank": "HEURISTIC | CONFIGURED",
  "expand": { "sentenceWindow": true, "parent": true },
  "deadlineMs": 8000
}
```

- 查询理解留在 Java（Q2）：`QueryRouter` 给出 `strategy`、改写后的主查询、子查询列表和文档范围收窄；Java 把收窄结果与可见集合求交，作为 `visibleDocumentIds` 下发。
- 执行语义迁到 Python：子查询分别检索、跨子查询 RRF 求和、原始查询入池（2026-11-07 修复后的口径）、证据充分性判断、有界回退，都按现有 `StrategyAwareRetrievalExecutor` 的语义移植。Java 工作包在删除前列出 `RetrievalStrategy` 的全部取值和各自语义，Python 一一对应；遇到未知取值返回 400。
- 现有调用方注入的 `EvidenceSufficiencyProbe`：Java 工作包核对现有实现。如果它只依赖候选文本和分数，就随执行器移植到 Python，按 `intent` 选用；如果依赖业务数据，回报中心 agent，不得自行加开关。
- 约束：
  - `queries` 1–5 条，恰好一条 `PRIMARY`，每条 1–2000 字符；
  - `topK ∈ [1, 20]`，`candidatePool ∈ [topK, 50]`；
  - **`visibleDocumentIds` 最多 5000 个，超过返回 400 `VALIDATION_ERROR`**（Q9）。Java 在调用前自己检查，超过就直接抛 `BusinessException(VALIDATION_ERROR)`（HTTP 400），不截断，也不调用 Python；
  - 空数组：返回 200 和 `hits=[]`，不访问数据库，也不租借凭据。
- 业务元数据过滤（文件名、创建和更新时间、状态）由 Java 先应用到 `visibleDocumentIds` 上，**不下发给 Python**。只有标题过滤下发，因为 `heading` 存在 `ai_index.chunk` 里。
- `rerank`（Q4）：Java 按 rerank 能力绑定决定。项目或全局存在已发布的 rerank 绑定时传 `CONFIGURED`，否则传 `HEURISTIC`。现有的 `configuredRerankEnabled` 配置项删除，是否存在绑定就是唯一依据。
  - `HEURISTIC`：Python 执行移植后的 `SemanticHeuristicReranker`。
  - `CONFIGURED`：Python 先租借 `RERANK`，再调用 reranker。只对融合排序的前 `min(n, params.maxCandidates)` 个候选重排，其余候选按融合顺序接在后面；这替代了现在"超过 8 个候选就回退启发式"的做法。**租借失败、reranker 超时或返回非法结果时，整次检索返回 503 `RERANK_UNAVAILABLE`，不退回启发式。**

响应 `200`：

```json
{
  "requestId": "uuid",
  "generationId": "uuid",
  "strategy": "原样回显",
  "hits": [ {
    "rank": 1, "documentId": "uuid", "chunkNo": 12, "contentHash": "sha256-hex",
    "chunkType": "CHILD", "parentChunkNo": 3, "heading": "...", "content": "该行原文",
    "bodySnapshotId": "uuid",
    "sourceRef": { "charFrom": 0, "charThrough": 980, "pageNumber": 3 },
    "context": { "text": "句子窗口或父块扩展后的文本",
                 "chunkRefs": [ { "chunkNo": 3, "contentHash": "sha256-hex" } ] },
    "scores": { "dense": 0.83, "lexical": null, "rrf": 0.0164, "rerank": 0.91, "final": 0.83 }
  } ],
  "diagnostics": { "denseCandidates": 20, "lexicalCandidates": 0, "iterativeScan": true,
                   "rerank": "HEURISTIC", "latencyMs": 123 }
}
```

- **引用身份就是 `documentId + chunkNo + contentHash`**（Q1），另带 `bodySnapshotId`，防止用过期索引作答。Python 内部的行 id 不对外返回。
- `content` 必须是该行原文，与 `contentHash` 对应。扩展出来的文本只放在 `context.text`，并在 `chunkRefs` 里列出用到的每一行，供 Java 逐行复核。`context` 可以为 `null`。
- `scores.final` 必须落在 `[0,1]`，契约测试要固定它的语义。2026-11-07 出过一次事故：RRF 分数替换了 similarity，结果掉到 0/54（`docs/archive/report/2026-11-07-rag-v2-quality-eval-final-report.md:54-64`）。Java 的装配阈值只能读 `final`。
- **（P2-R1 修订）`scores.final` = 准入分 = `max(dense 余弦, lexical 匹配强度)`**，缺失的一路不参与取最大值，两路都缺失时为 `0`；语义与 Java 现行 `RetrievalScores` 完全一致。`rrf`、`rerank` 只表示排序，**不得进入 `final`**。hits 的顺序就是最终排名（融合 → 重排），Java 不按 `final` 重排。
- 不返回文件名：文件名属于业务数据，由 Java 按 `documentId` 回填。
- `diagnostics` 里不能有查询原文、正文和 key。

状态码（Python → Java；Java 只按 HTTP 状态分支，与 P1 §9.1 一致）：

| HTTP | code | 含义 / Java 处理 |
|---|---|---|
| 200 | — | 成功（包括空结果） |
| 400 | `VALIDATION_ERROR` | 请求格式错误、`visibleDocumentIds` 超过 5000、未知 `strategy`；不重试 |
| 401 | `UNAUTHORIZED` | 令牌无效或缺 scope；不重试 |
| 403 | `SEARCH_FORBIDDEN` | 令牌 `pid/uid` 与请求体不符；不重试 |
| 409 | `INDEX_GENERATION_UNAVAILABLE` | 当前没有 ACTIVE generation，或者查询向量模型与 ACTIVE generation 的模型不一致；Java 返回"知识库索引不可用"，不重试 |
| 503 | `AI_PROVIDER_UNAVAILABLE` / `AI_PROVIDER_PARAMS_INVALID` / `RERANK_UNAVAILABLE` | 租借或 reranker 失败。Java 最多重试 1 次，仍失败就以 503 返回给前端 |
| 504 | `SEARCH_DEADLINE_EXCEEDED` | 超过 `deadlineMs`；不重试 |
| 其他 5xx | `INTERNAL_ERROR` | 同 503，重试 1 次（检索只读，天然幂等）。**都不回落到 Java 检索** |

- 幂等：检索只读，`requestId` 只用于日志关联和用量回报。

### 4.5 可见文档

设计 §4.4 列了 `GET /internal/ai/projects/{p}/visible-documents`，供 Python 主动拉取。**P2 不实现它**：P2 的检索调用方全部在 Java，由 Java 在请求体里下发。P3、P4 如果需要 Python 主动拉取，在对应阶段的契约里另定。

### 4.6 generation 管理（Java → Python，scope `index:admin`）

| 接口 | 用途 | 幂等 |
|---|---|---|
| `GET /v1/index/generations` | 列出所有 generation 的状态、模型、维度、行数，供管理页展示（Java 不落库） | 只读 |
| `POST /v1/index/generations/{id}:reconcile` `{documentIds:[≤5000]}` | 返回这一批里在该 generation 下没有 `indexed_document` 记录的文档 ID | 只读 |
| `POST /v1/index/generations/{id}:activate` | `BUILDING → ACTIVE`，原 ACTIVE → `RETIRED`，同一事务 | 已是 ACTIVE 时返回 200 |
| `POST /v1/index/generations/{id}:abandon` | `BUILDING → FAILED` | 已是 FAILED 时返回 200 |

- 0 个切块的文档也要能对账：Python 在 `ai_index.indexed_document` 里给它记一行（§5.1），`:reconcile` 以这张表为准。
- 激活后，已退役 generation 的索引行由 Python 后台清理，只保留最近一个 RETIRED，便于重新激活回滚；这一步不需要 Java 参与。

## 5. 数据库

### 5.1 `ai_index` 表结构（Alembic 管理，属主 `ai_worker`）

```sql
CREATE TABLE ai_index.index_generation (
  id              uuid PRIMARY KEY,
  embedding_model varchar(120) NOT NULL,
  dimension       integer NOT NULL CHECK (dimension = 1024),
  status          varchar(16) NOT NULL CHECK (status IN ('BUILDING','ACTIVE','RETIRED','FAILED')),
  created_at      timestamptz NOT NULL DEFAULT now(),
  activated_at    timestamptz
);
-- 全站只有一个 ACTIVE、最多一个 BUILDING（Q5）
CREATE UNIQUE INDEX uk_index_generation_active   ON ai_index.index_generation ((true)) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_index_generation_building ON ai_index.index_generation ((true)) WHERE status = 'BUILDING';

-- 每个 (generation, document) 一行，0 个切块的文档也有记录，用于对账
CREATE TABLE ai_index.indexed_document (
  generation_id    uuid NOT NULL REFERENCES ai_index.index_generation(id) ON DELETE CASCADE,
  project_id       uuid NOT NULL,
  document_id      uuid NOT NULL,
  body_snapshot_id uuid NOT NULL,
  row_count        integer NOT NULL CHECK (row_count >= 0),
  indexed_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (generation_id, document_id)
);

CREATE TABLE ai_index.chunk (
  id               uuid PRIMARY KEY,
  generation_id    uuid NOT NULL REFERENCES ai_index.index_generation(id) ON DELETE CASCADE,
  project_id       uuid NOT NULL,          -- 不建跨 schema 外键
  document_id      uuid NOT NULL,
  body_snapshot_id uuid NOT NULL,
  chunk_no         integer NOT NULL,       -- = document_body_chunk.chunk_no
  chunk_type       varchar(16) NOT NULL CHECK (chunk_type IN ('STANDARD','PARENT','CHILD','SENTENCE')),
  parent_chunk_no  integer,
  heading          varchar(300),
  content          text NOT NULL,
  content_hash     char(64) NOT NULL,      -- = document_body_chunk.content_hash
  metadata         jsonb NOT NULL DEFAULT '{}',
  embedding        vector(1024),           -- PARENT 行不向量化，为 NULL
  created_at       timestamptz NOT NULL DEFAULT now(),
  UNIQUE (generation_id, document_id, chunk_no),
  CHECK ((chunk_type = 'PARENT') = (embedding IS NULL))
);
CREATE INDEX idx_chunk_hnsw ON ai_index.chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_chunk_content_trgm ON ai_index.chunk USING gin (content gin_trgm_ops);
CREATE INDEX idx_chunk_heading_trgm ON ai_index.chunk USING gin (heading gin_trgm_ops);
CREATE INDEX idx_chunk_scope ON ai_index.chunk (generation_id, project_id, document_id);
CREATE INDEX idx_chunk_parent ON ai_index.chunk (generation_id, document_id, parent_chunk_no) WHERE parent_chunk_no IS NOT NULL;
```

- **维度（Q7）**：固定为 `vector(1024)`，`index_generation.dimension` 用 CHECK 锁死成 1024。租到的 embedding 模型输出维度不是 1024 时，`REINDEX_GENERATION` 上报 fail(`EMBEDDING_DIMENSION_MISMATCH`, retryable=false)，不创建 generation。P2 不支持在线换维度；以后要换，必须另发版本，用 Alembic 加列或加表，再新建 generation 全量重建。这满足 AGENTS.md §5"维度与模型实际输出一致、不硬编码 1536"，只是 1024 写进了 DDL。
- **向量化输入**：Python 为每行构造的输入文本，必须与 Java 现行实现（`DocumentProcessingService` / `ProjectEmbeddingGateway`，`EmbeddingTaskType.DOCUMENT/QUERY`）**逐字一致**，包括是否拼接 heading、截断长度、文档侧和查询侧的指令前缀等。Java 工作包在删除旧代码前把现行规则写进汇报，Python 照做，并补等价测试（§9.3）。
- **检索过滤**：检索 SQL 必须同时带 `generation_id = :active`、`project_id = $1`、`document_id = ANY($2)`、`chunk_type <> 'PARENT'`。
- **HNSW 参数（Q10）**：P2 先用 pgvector 默认值，`hnsw.ef_search`、`hnsw.max_scan_tuples` 都不改，只在每个检索事务里执行 `SET LOCAL hnsw.iterative_scan = relaxed_order`。`relaxed_order` 返回的结果可能略有乱序，所以外层要再按距离排一次：

  ```sql
  WITH c AS MATERIALIZED (
    SELECT id, document_id, chunk_no, embedding <=> $q AS distance
    FROM ai_index.chunk
    WHERE generation_id = $g AND project_id = $p AND document_id = ANY($ids) AND chunk_type <> 'PARENT'
    ORDER BY embedding <=> $q LIMIT $pool)
  SELECT * FROM c ORDER BY distance;
  ```

  P2 不实现"可见文档占比低时走精确扫描"的回退。E1 实测之后再调参，结果以新的契约修订写进本文；E2 不必等 E1。
- **版本检查**：Python 启动时执行 `SELECT extversion FROM pg_extension WHERE extname='vector'`。版本低于 0.8 时 `/readyz` 返回 not ready、不接任务，并写进报告（§18）。
- **词法阈值**：`SET LOCAL pg_trgm.similarity_threshold = 0.03`，与现有 GUC 一致。

### 5.2 角色与权限

- 沿用 `db-init.sql`，不新增角色。Alembic 以 `ai_worker` 身份执行；它是 `ai_index` 的属主，本来就有 CREATE 权限。扩展 `vector` 和 `pg_trgm` 已由超级用户预装在 public。
- Python 新增连接串变量 `AI_INDEX_DATABASE_URL`，属于敏感值，由 P2-OPS 落到 staging，并纳入 P1 §2.5 的脱敏规则。
- 要用集成测试确认：`ai_worker` 只靠 PUBLIC 默认的 USAGE，就能在 `ai_index` 里建 `gin_trgm_ops` 和 `vector_cosine_ops` 索引。

### 5.3 generation 指针

- **已决定（Q5）**：全站只有一套 generation，Java **不建镜像表**，原草案的 V107 取消。
- 检索时由 Python 自己读取 ACTIVE generation。Java 只在重建任务的 `ai_job.generation_id` 上记一个参数；展示用的信息通过 `GET /v1/index/generations` 实时查询。
- 新旧两套 generation 互不相干：Java 的 `embedding_index_generation` 在 P2 停用（写路径删除），到 P7 删表。

### 5.4 Java 迁移（从 V105 起）

| 版本 | 内容 |
|---|---|
| `V105__p2_ai_job_index_types_and_rerank_lease.sql` | `ck_ai_job_type` 加 `INDEX_DOCUMENT`、`DELETE_DOCUMENT_INDEX`、`REINDEX_GENERATION`；`ai_job` 增加可空列 `document_id uuid`、`generation_id uuid`、`parent_job_id uuid REFERENCES ai_job(id) ON DELETE CASCADE`，以及索引 `(parent_job_id, status)`。如果 V91 已有能承载这些信息的列就复用，由 Java 工作包核对后在汇报里说明。另外 `ck_ai_credential_lease_purpose` 加 `RERANK`（§12 P1-R4） |
| `V106__p2_citation_identity.sql` | `knowledge_citation`：<br>• 删除对 `document_chunk(id)` 的外键，`chunk_id` 改为可空，只留给历史行；<br>• 新增 `document_id uuid REFERENCES project_document(id) ON DELETE CASCADE`、`chunk_no integer`、`content_hash char(64)`、`body_snapshot_id uuid`；<br>• 从 `document_chunk` 回填历史行的 `document_id/chunk_no/content_hash`，回填后把 `document_id` 设为 `NOT NULL`；<br>• 加 `CHECK (chunk_id IS NOT NULL OR (chunk_no IS NOT NULL AND content_hash IS NOT NULL))` |

不修改任何历史迁移。`document_chunk`、`embedding_index_generation`、`embedding_index_document` 保留到 P7 再删。

### 5.5 从现有表迁数据

**不复制向量**，原因有三：
1. 现有向量绑定的是 Java 的指纹和 generation，和新表的 generation 体系对不上；
2. Java 角色不能写 `ai_index`，Python 角色不能读 `document_chunk`。要跨角色复制只能用超级用户，这违背隔离要求；
3. 设计 §5.4 明确要求从 `document_body` 对全部 READY 文档**重建**。

所以迁数据就是首次全量重建：在切换窗口里走一次 §4.2 的重建流程。`document_body_chunk` 本身就是现成的切块结果，不需要重新解析。

## 6. 引用复核（Java）

Python 返回的每一条 hit，Java 按顺序做下面的校验。任意一条不满足就丢弃，并计入 `knowledge_citation_rejected_total{reason}`：

1. `documentId ∈ visibleDocumentIds`，即属于本次请求下发的集合；
2. 文档当前仍是 READY、未封锁、可读。检索期间文档可能被撤权，这里复用现有的可见性 SQL；
3. `bodySnapshotId == document_body.snapshot_id`；
4. `document_body_chunk` 中存在 `(document_id, chunk_no)` 这一行，且 `content_hash` 相等；
5. `context` 不为空时，`chunkRefs` 里的每一行都按第 4 条复核，有一行不通过就整条丢弃（fail-closed）；
6. 丢弃的条目不进上下文，也不落 `knowledge_citation`。如果全部被丢弃，按"证据不足"处理，不能当作检索成功。

正文以 Java 从 `document_body_chunk.content` 读到的内容为准，Python 返回的文本只用来比对。复核通过后，引用按 `document_id + chunk_no + content_hash + body_snapshot_id` 落库（V106）。

需要改成这套身份复核的地方：
- `EvidenceVerificationService.fetchVerifiedChunks`
- `KnowledgeCitationValidator`
- `KnowledgeCitationMapper:42-48`
- `KnowledgeMessageMapper:23-26`
- `AgentRepository:1158`

规划的 `seedEvidence`（`SeedEvidenceInput`）也改用 `documentId + chunkNo + contentHash`，不再接受 Agent 传入的旧 `chunkId`。这部分是 P2 在 Java 侧的主要工作量。

## 7. 切换方案（一次性，不留开关）

不保留 java/python 双模式开关，不做 shadow，不做进程内回落。具体要删除的是：`retrieval.force-legacy-dense`、`Mode.DENSE_LEGACY`、`RetrievalQaProperties` 里的模式选择、`configuredRerankEnabled`，以及 `ConfiguredReranker` 失败时回退启发式的逻辑。

**部署前置检查（Q14）**：由运维子 agent 只读执行，任意一项不满足就停止部署并报告。
1. 在 staging 执行 `SELECT extversion FROM pg_extension WHERE extname='vector'`，版本必须 ≥ 0.8，结果写进部署报告；
2. 确认 `db-init.sql` 已执行：`ai_index` 的属主是 `ai_worker`，`ai_collab_app` 对 `ai_index` 没有 USAGE；
3. `AI_INDEX_DATABASE_URL` 已配置，检查时不回显它的值；
4. 记录当前 READY 文档数和 `document_body_chunk` 总行数，作为对账基准。
5. （P2-R3）执行 §12.5 R3-2 的只读统计 SQL，记录缺 `document_body` 的 READY 文档数；再用补正文任务的 dry-run 记录其中原始文件仍在对象存储里的数量。两个数都在 staging 实测，写进部署报告。缺正文的文档没有补齐或标记为 FAILED 之前，不进入切换步骤 3。

**切换步骤（staging）：**
1. 进入维护模式，关闭上传和问答入口；用 `pg_dump` 做全库备份，记录备份文件的 SHA-256。
2. 部署新版本。Java 侧：V105、V106、Python 检索客户端、新的引用复核。Python 侧：Alembic 建 `ai_index`、Stream 消费三类索引任务、检索接口。这个版本里旧的 Java 检索代码已经删除。
3. 管理员发起 `REINDEX_GENERATION`。此时还没有 ACTIVE generation，检索返回 409 `INDEX_GENERATION_UNAVAILABLE`，前端显示索引不可用。
4. 按 §4.2 扇出，等全部子任务成功，`:reconcile` 无缺失后调用 `:activate`。另外抽样 10 篇文档，按 §6 的规则做复核。
5. 在 staging 跑越权冒烟，本地按 §9.1 跑 39 题评测并如实记录，然后退出维护模式。

**回滚（不靠开关）：**
- **代码**：回退到切换前的 `dev` 提交并重新部署。旧代码读的是 `document_chunk`，P2 不删这张表，旧数据完好。
- **数据库**：V105、V106 只增不改。V106 去掉了外键、把 `chunk_id` 改成可空，旧代码写入时总会带 `chunk_id`，读写都不受影响。
- **引用失效（已接受，Q11）**：切换期间新产生的引用 `chunk_id` 为空，只有 `document_id + chunk_no + content_hash`，旧代码 JOIN 不到 `document_chunk`，回滚后这些引用在界面上**不再显示**。这是已知损失，不做补救；回滚报告里写明受影响的引用条数（`SELECT count(*) FROM knowledge_citation WHERE chunk_id IS NULL`）。
- **新上传文档**：切换期间不再写 `document_chunk`，所以这段时间新上传的文档在旧代码下没有向量，回滚后要重新索引。这一点也写进回滚报告。
- **整库恢复**：只有业务数据本身损坏时，才用第 1 步的 `pg_dump` 恢复，代价是丢掉切换后的业务写入。
- **索引数据坏了**：重新走一次重建并激活，或者重新激活最近一个 RETIRED generation，不需要回滚代码。（P2-R4 R4-4）重新激活 RETIRED generation 必须按顺序做完下面三步，才能调 `:activate`：
  1. 对这个 generation 跑一轮完整的 PRUNE（§12.6 R4-3，全部批次），删掉它退役期间已删除或已失效的文档；
  2. 对这个 generation 跑 `:reconcile`（只读模式），对当前"应被索引"的全部文档查缺失，为缺失的文档补派 `INDEX_DOCUMENT`（带这个 `generationId`，`parent_job_id` 指向本次回滚的父任务）；
  3. 补派的子任务全部成功，并且再跑一轮 `:reconcile` 确认没有缺失。

  补派任务会碰到 §4.2 对 RETIRED generation 的限制，见 §12.6 R4-4 待裁决。

## 8. P2 删除清单（Java）

- **删除检索执行部分**：
  - `retrieval/pipeline/` 下的 `DenseRetriever`、`LexicalRetriever`、`ReciprocalRankFusion`、`SemanticHeuristicReranker`、`NeuralModelReranker`、`ConfiguredReranker`、`PassThroughReranker`、`SentenceWindowExpander`、`ParentChildExpander`、`ParentChunkReader`、`PostgresDocumentSectionReader`、`RetrievalPipeline`；
  - `retrieval/rerank/RerankExecutionPolicy`（逻辑移到 Python）；
  - `StrategyAwareRetrievalExecutor`（执行语义迁到 Python，§4.4）。
- **删除写入和向量化**：
  - `DocumentRepository.search` / `replaceChunks`、`DocumentMapper` 中检索和 `insertChunk` 的 SQL、`DocumentIndexWriter`、`BatchReindexService`；
  - `ProjectEmbeddingGateway`、`OpenAiCompatibleEmbeddingGateway` 中文档和查询向量化的调用；
  - `infrastructure/ai/capability/LlamaCppRerankAdapter`。
- **保留到 P3**（Q2）：`QueryRouter`、`QueryUnderstandingService`、`MultiQueryDecomposer`、`MetadataFilterParser`、`QueryPlanGatewayClient`，以及它们用到的 `retrieval/query/` 模型类。
- **保留并改造**：
  - `RerankCapabilityResolver` 改为给 `RERANK` 租借做路由解析，能力配置的增删改查照旧；
  - `DocumentChunker` 不动；`DocumentContentService.saveParsed` 只改成用切块器 id 作为行 id（§4.3）；
  - `ProjectKnowledgeRetrieval` 改成薄门面：成员校验 → 查询理解 → 计算 `visibleDocumentIds` → 选择 `rerank` → 调用 Python → 复核 → 回填文件名。
- **删除后的证明**：用 grep 证明 `src/main` 里不再出现 `document_chunk` 的检索 SQL、`<=>`、`force-legacy-dense`、`DENSE_LEGACY`、`CONFIGURED_RERANK`、`configuredRerankEnabled`、`LlamaCppRerankAdapter`。

## 9. 验收（P2 门）

### 9.1 质量（E2，硬门槛，已决定 Q12）
- 39 题评测改写成 Python 脚本或 pytest。冻结语料和问题清单按 SHA-256 引用 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`，不复制、不修改。评测走真实链路：Java 解析切块 → Python 建索引 → Java 下发可见集合 → Python 检索 → Java 复核。
- **门槛：QA 口径必要事实保留 ≥ 52/54**，同时报告空上下文数。
- **每一轮都如实记录**，失败的轮次也写进 `docs/benchmark/2026-11-xx-ai-service-split/`。不靠重跑挑好结果，不取最好一轮，也不取中位数。
- Agent 工具口径单独记录，参考值 53/54，**不作为门槛**。
- 报告要如实标注：自建合成题集、字符串匹配判定、embedding 模型、pgvector 版本、HNSW 参数（默认值 + iterative scan）、`rerank` 取值。
- 低于 52/54 时，按设计 §18 停在 P2，报告逐题归因，不进入 P3。

### 9.2 越权与隔离
1. **数据库角色**（Java Testcontainers，先跑 `db-init.sql`）：
   - 以 `ai_collab_app` 连接，`SELECT 1 FROM ai_index.chunk` 报 `permission denied for schema ai_index`，`INSERT` 同样被拒；
   - 以 `ai_worker` 连接，`SELECT 1 FROM public.project_document` 被拒，`CREATE TABLE public.x` 被拒；
   - Python 侧用 pytest + testcontainers 做对称断言。
2. **ACL**：
   - 下发集合不含文档 B 时，即使 B 与查询最相关，结果里也没有 B；
   - 空集合返回 200 和空结果，且不发起租借；
   - 传 5001 个 ID 时 Python 返回 400；Java 侧超过 5000 时不发请求，直接返回 400；
   - 令牌 `pid/uid` 与请求体不符时返回 403；
   - 集合里混进别的项目的 documentId 时，因为有 `project_id = $1` 过滤，不会返回。
3. **引用复核**：mock 的 Python 返回以下几种 hit，Java 全部丢弃、计数、不落库：
   - 集合外的 documentId；
   - 过期的 `bodySnapshotId`；
   - 不存在的 `chunkNo`；
   - `contentHash` 不符；
   - `chunkRefs` 里有一行不符。
4. **撤权竞态**：检索完成后、复核前把文档改为 `access_blocked`，复核时丢弃。
5. **rerank 不回落**：
   - `CONFIGURED` 下让 reranker 超时、返回非法响应、或让租借返回 503，检索都返回 503 `RERANK_UNAVAILABLE`，结果里不出现启发式排序；
   - `HEURISTIC` 下不发起 `RERANK` 租借。
6. **HNSW 过滤召回**（E1 的子项，只记录，不卡门）：可见文档占 10% 时，分别记录 iterative scan 开 / 关的 Recall@10。

### 9.3 重建索引正确性
1. **幂等**：同一 `INDEX_DOCUMENT` 执行两次，行数和 `(chunk_no, content_hash)` 集合完全相同，没有重复行。
2. **并发**：同一文档两个 epoch 并发执行，旧 epoch 的 result 返回 409，最终只有一份索引行。
3. **快照变化**：索引期间重新上传，旧任务的 result 返回 409 `INDEX_SNAPSHOT_STALE`，最终索引行绑定新的 snapshot。
4. **一一对应**：对 23 篇冻结语料，每个 generation 下 `ai_index.chunk` 与 `document_body_chunk` 的行数、`chunk_no`、`chunk_type`、`content_hash` 逐行一致；PARENT 行 `embedding IS NULL`，其余行不为空。
5. **父块解析**：
   - 新数据（行 id 为切块器 id）和存量数据（按偏移规则）都能解析出正确的 `parentChunkNo`；
   - 构造一个解析不到的情况，结果为 `null`、指标加 1、不报错。
6. **向量化输入等价**：同一行，Python 构造的 embedding 输入文本与 Java 现行规则逐字一致，文档侧和查询侧都要测。
7. **检索执行等价**：
   - 删除 Java 检索前，用现有 Java 管线对 39 题导出两类金标准夹具：每题最终 hits 的 `(documentId, chunkNo, contentHash)` 序列，以及启发式重排的输入和输出；
   - Python 的 RRF、启发式重排、策略执行，对同样的输入逐题给出相同结果；
   - 向量检索本身因为 HNSW 是近似检索，只比对 E2 结果，不比对逐项排序。
8. **空正文**：0 个切块的文档，`INDEX_DOCUMENT` 成功，`indexed_document.row_count = 0`，文档为 READY，检索不返回它，`:reconcile` 也不把它当作缺失。
9. **重建全流程**：
   - 扇出 → 子任务全部成功 → reconcile → activate 能走通；
   - 重建期间常规上传的文档同时写进 ACTIVE 和 BUILDING；
   - 激活前检索仍走旧的 ACTIVE；
   - 两个扫描器并发执行，只激活一次；
   - 有子任务终态失败时，generation 变为 FAILED，ACTIVE 不变。
10. **维度不符**：租到的模型输出不是 1024 维时，`REINDEX_GENERATION` 上报 fail(`EMBEDDING_DIMENSION_MISMATCH`)，不创建 generation。
11. **删除**：`DELETE_DOCUMENT_INDEX` 之后，所有 generation 下都没有该文档的行；删除任务还没执行时，检索也不会返回它，因为 Java 不再下发该文档。
12. **worker 崩溃**：执行中 kill worker，经 XAUTOCLAIM 或补偿扫描接管后，结果与正常执行一致。

### 9.4 其他
- **E1**（设计 §13 P2 门）：DuRetrieval 10 万语料，精确扫描对比 HNSW，`ef_search` 取 40/80/160，ACL 10% 时 iterative scan 开 / 关。结果用于后续调参，不阻塞 E2。
- **凭据**：沿用 P1 哨兵测试，覆盖 `EMBEDDING` 和 `RERANK` 租借，以及检索的报错路径。
- **依赖**：`test_forbidden_dependencies.py` 的白名单正好放行 §1.6 列出的几项。
- **全量测试**：后端 `mvnw verify`、Python 全量 pytest 通过。

## 10. 指标与日志

- 指标（都带 `caller_type` 标签）：
  - `ai_index_task_duration_seconds{taskType}`
  - `knowledge_search_latency_seconds{intent,strategy}`
  - `knowledge_search_hits{intent}`
  - `knowledge_search_errors_total{status}`
  - `knowledge_citation_rejected_total{reason}`
  - `ai_index_generation_rows{status}`
  - `document_body_parent_unresolved_total`
- 用量：`RERANK` 调用也按 P1 §3 回报，token 数为 0，只记录 `latencyMs` 和 `status`。
- 日志：沿用 P1 §2.5 的脱敏规则。此外**不记录查询原文和 chunk 正文**，只记录 `requestId`、`projectId`、命中数和耗时，与现有 `ConfiguredReranker` 的日志纪律一致。

## 11. 决定记录（中心 agent 于 2026-11-27 代用户拍板）

| # | 问题 | 决定 | 落到正文 |
|---|---|---|---|
| Q1 | 切块与引用身份 | **方案 B**：Java 继续用 `DocumentChunker` 写 `document_body_chunk`；Python 只负责向量化、建索引和检索。引用身份为 `documentId + chunkNo + contentHash`，Java 按这三项复核 | §0、§3、§4.3、§4.4、§5.1、§6、§9.3、§12 D1 |
| Q2 | 查询理解什么时候迁 | 与问答一起在 P3 迁；P2 只迁索引和检索（执行器语义算检索，迁到 Python） | §0、§3、§4.4、§8、§12 D2 |
| Q3 | `/v1/knowledge/search` 的去留 | 作为内部检索接口保留，P3、P4 也复用；前端看不到 | §4.4、§4.5、§12 D3 |
| Q4 | rerank | 租借用途新增 `RERANK`（对 P1 的修订，P2 由 Java 实现）；默认的启发式重排迁到 Python；`CONFIGURED` 失败直接返回错误，不再静默退回启发式 | §4.1、§4.4、§5.4、§8、§9.2、§12 D4 / P1-R4 / P1-R5 |
| Q5 | generation 粒度 | 全站一个；Java 不建镜像表，V107 取消 | §4.2、§5.1、§5.3、§5.4、§12 D5 |
| Q6 | 重建方式 | 逐篇派发 `INDEX_DOCUMENT`；`REINDEX_GENERATION` 只负责创建 generation；扇出、完成后的对账与激活由 Java 依据数据库状态推进，补偿扫描兜底 | §4.2、§4.6、§9.3 |
| Q7 | 维度 | 固定 `vector(1024)`；要换维度就新建 generation 全量重建，P2 不支持在线换维度 | §5.1、§9.3 |
| Q8 | 空正文 | 文档为 READY，0 个切块 | §4.2、§4.6、§5.1、§9.3、§12 D6 |
| Q9 | 可见集合上限 | 最多 5000，超过返回 400；空集合返回空结果 | §4.4、§9.2 |
| Q10 | HNSW 参数 | 先用 pgvector 默认参数并开启 iterative scan，E1 实测后再调；E2 可以先跑 | §5.1、§9.4、§12 D7 |
| Q11 | 回滚后切换期间新增的引用失效 | 接受，写进回滚步骤 | §7 |
| Q12 | E2 门槛 | 按设计，QA 口径 ≥ 52/54；每一轮如实记录，不靠重跑挑好结果；Agent 口径单独记录、不卡门；不达标就按 §18 停下报告 | §9.1 |
| Q13 | Python 依赖 | 允许新增数据库驱动、pgvector 适配包和 Alembic，同步更新 `test_forbidden_dependencies.py` 的白名单 | §1.6、§9.4 |
| Q14 | staging 的 pgvector 版本 | 部署前由运维子 agent 只读确认，列为部署前置检查项 | §1.4、§7 |

## 12. 对上位设计与 P1 契约的修订

### 12.1 对设计 §5（及 §3、§4.5、§11）的修订

| 编号 | 原文 | 修订 | 原因 |
|---|---|---|---|
| D1 | §3 表格与 §5.2 第 3 步："Python 认领任务，分页拉取正文，切块（迁移现有 `DocumentChunker` 规则）" | 切块留在 Java（`DocumentChunker` → `document_body_chunk`）；Python 只拉取已切好的块，负责向量化和建索引。§4.3 的正文接口改为返回块，而不是全文 | ① **引用身份稳定**：`document_body_chunk` 已经是引用、Agent 证据和章节回读的事实来源，`(documentId, chunkNo, contentHash)` 在 Java 和 Python 两侧天然一致，Java 复核只需比对一行，不用按偏移拼接文本。② **评测基线没有余量**：52/54 正好等于门槛，移植切块规则时，标题推断、代码块边界、代理对、句子边界上任何细微差异都可能直接掉到门槛以下。切块不动，P2 的变量就只剩向量检索本身 |
| D2 | §11 删除清单把"查询理解"和检索一起列入 P2 | 查询理解（`QueryRouter`、多查询分解、元数据过滤解析、`/v1/rag/plan`）随 P3 的问答一起迁；P2 只迁索引和检索执行 | 控制 P2 的变量；查询改写和问答生成放在 P3 一起迁更自然 |
| D3 | §4.5 中 Python 只提供 `/v1/knowledge/answer:stream` | 新增内部接口 `POST /v1/knowledge/search`，作为检索的唯一出口，P3–P5 复用 | P2 的调用方都在 Java；P3 以后 Agent 工具和规划仍需要单独检索 |
| D4 | §5.3 只写了"RRF 融合之后接 llama.cpp reranker"，没写失败怎么处理；现状是失败时静默回退启发式 | 默认的启发式重排迁到 Python；配置了 reranker 绑定就用它，失败时整次检索返回 503，不回退 | 审查清单要求"不保留进程内回落"；失败就是失败，不能包装成成功 |
| D5 | §5.1 的 `index_generation` 带 `project_id` | 全站一套 generation，不带 `project_id`；Java 不镜像 | 与现有 `embedding_index_generation`（全站级）一致，切换和对账都更简单 |
| D6 | §5.4 "校验每个文档的 chunk 数非零后切换" | 改为"每篇 READY 文档的索引行数等于其 `document_body_chunk` 行数（可以为 0）"；空正文文档为 READY、0 个切块 | Q8 |
| D7 | §5.3 把"可见文档占比 < 阈值时走精确扫描"作为低版本回退 | P2 只支持 pgvector ≥ 0.8 + iterative scan；版本低于 0.8 时 worker 不就绪，停止部署并报告 | 本地实测为 0.8.6；staging 由部署前置检查确认 |

### 12.2 对 P1 契约的修订（P1-R4、P1-R5，由 P2 实现）

**P1-R4：租借用途新增 `RERANK`**
- P1 §2.1 的 `purpose` 枚举增加 `RERANK`。Java 把它映射到 `RerankCapabilityResolver` 现有的解析顺序：先项目绑定，再全局绑定。解析不到可用绑定时返回 503 `AI_PROVIDER_UNAVAILABLE`（retryable=true）。
- V105 同步修改 `ck_ai_credential_lease_purpose`。
- 检索令牌的 `purp` 为 `["EMBEDDING","RERANK"]`；索引任务令牌只有 `["EMBEDDING"]`。

**P1-R5：租借协议新增 `LLAMACPP_RERANK`**
- P1 §2.3 / §9.4 的 `protocol` 枚举增加 `LLAMACPP_RERANK`，Python 侧对 protocol 的校验同步放行这个值。
- 这种 lease 的 `params` 为 `{maxCandidates, timeoutMs}`，取自 `RerankExecutionPolicy.inlineSettings` 的现行值；`capabilities` 为 `["RERANK"]`。
- 提供商没有配置凭据时 `apiKey` 为 `null`，Python 不发送 Authorization 头。
- 其余字段和安全约束不变：不落盘、不进日志、用 `SecretStr`、响应带 `no-store`。

### 12.3 P2-R1（中心 agent 于 2026-11-27 裁决，Python 工作包开工前提出的 6 个问题）

本节优先级等同 §12.1/§12.2；与正文冲突时以本节为准。

| 编号 | 问题 | 裁决 | 影响正文 |
|---|---|---|---|
| R1-1 | Alembic 依赖 SQLAlchemy（禁入前缀），`pgvector` Python 包带进 numpy，白名单做不到"恰好三项" | **不用 Alembic / SQLAlchemy**。`ai_index` 迁移改为 Python 自管的版本化 SQL 文件：<br>• `ai_index.schema_migrations` 记录已执行版本；<br>• 执行前 `pg_advisory_lock`，多实例并发启动不重复执行；<br>• 每个迁移一个事务；<br>• 已发布迁移只增不改。<br>`pgvector` Python 包如会带进 numpy 则不用，向量以文本字面量 `'[...]'::vector` 写入。`test_forbidden_dependencies.py` 白名单按实际新增依赖精确放行，**不放宽禁入前缀** | §1.6、§3、§5.1、§5.2、§9.4、Q13 |
| R1-2 | `scores.final` 语义未定义，示例 `final = rerank` 与 Java `RetrievalScores` 冲突 | `final` = 准入分 = `max(dense 余弦, lexical 匹配强度)`，与 `RetrievalScores` 完全一致；`rerank` 单独放 `scores.rerank`，只用于排序，不进 `final`。Java 0.15 阈值只读 `final`。§4.4 示例已改 | §4.4 |
| R1-3 | 索引任务令牌没有 `uid`，`REINDEX_GENERATION` 没有项目，无法按 P1 规则租 `EMBEDDING` | 系统级 `EMBEDDING` 租借：请求带 `taskId` 时 `userId`、`projectId` 可为 `null`。Java 按 `tid` 校验：`ai_job` 存在、状态 `LEASED`/`RUNNING`、`ep` 与令牌一致、`purp` 含 `EMBEDDING`、任务类型 ∈ {`INDEX_DOCUMENT`, `REINDEX_GENERATION`}；`INDEX_DOCUMENT` 还要校验 `pid` 等于该 job 所属项目。不带 `taskId` 的请求规则不变（`userId` 必填）。Python `LeaseRequest` 只在带 `task_id` 时放宽 | §4.1、P1 §2.1 |
| R1-4 | lease 不带维度、批大小，也没有 Gemini 原生 embedding 协议 | `EMBEDDING` lease 的 `params = {dimensions: int\|null, batchSize: int}`；`dimensions = null` 表示请求体不带 `dimensions`（OLLAMA 一律下发 `null`）。`protocol ∈ {OPENAI_EMBEDDINGS, GEMINI_EMBEDDINGS}`，Gemini 走原生 `batchEmbedContents`，文档侧 `taskType=RETRIEVAL_DOCUMENT`、查询侧 `RETRIEVAL_QUERY`。**不做维度试探**：用第一批真实分块的向量化结果与向量列维度（1024）比对，不符即 fail(`EMBEDDING_DIMENSION_MISMATCH`, retryable=false)，不写入任何行。向量化输入沿用 Java 现状：文档侧 = chunk `content` 原文，查询侧 = `query.trim()`，不拼标题、不加前缀 | §4.2、§5.1、§9.3 第 6/10 条、P1 §2.3 |
| R1-5 | RRF / 启发式重排的平局按 Java `document_chunk.id` 打破，Python 无此 id，逐题等价无法成立 | 两侧统一按 `(documentId, chunkNo)` 升序打破平局。Java 先改，重跑 39 题基线记录实测分数后再导出金标准夹具；Python 按新夹具逐题比对 | §9.3 第 7 条 |
| R1-6 | `RetrievalStrategy` 取值与语义、`EvidenceSufficiencyProbe` 可否移植、向量化输入规则依赖 Java 汇报 | 由 Java 工作包先出书面报告；Python 在拿到报告前**不实现** `strategy` / `queries` 的执行部分，不靠推断 | §4.4 |

### 12.4 P2-R2（中心 agent 于 2026-11-27 裁决，Java 现行检索语义报告 §4 的 4 个问题与平局处理）

本节优先级等同 §12.1–§12.3；与正文或 P2-R1 冲突时以本节为准。Java 现行语义见 `docs/design/p2-java-retrieval-semantics-2026-11-27.md`（下称"语义报告"）。

| 编号 | 问题 | 裁决 | 影响正文 |
|---|---|---|---|
| R2-1 | 现行管线有三处"出异常就静默降级"：策略执行异常后重跑 HYBRID；Dense 或词法异常后变成空候选；重排异常后按融合顺序截断（语义报告 §1、§4-1） | **全部删除，不做降级**。三种情况都让整次检索返回 503：<br>• Dense（含查询向量化、`EMBEDDING` 租借）失败 → `RETRIEVAL_DENSE_UNAVAILABLE`；<br>• 词法失败 → `RETRIEVAL_LEXICAL_UNAVAILABLE`；<br>• 重排失败（启发式或 `CONFIGURED`）→ `RERANK_UNAVAILABLE`。<br>`DENSE_WITH_BOUNDED_FALLBACK` 的"Dense 优先、证据不足时最多补一次 HYBRID"**保留**：它由证据充分性判据触发，是正常的策略分支，不是出错回退，语义按语义报告 §1、§2 原样移植。补跑的 HYBRID 本身出错时同样返回 503 | §4.4 状态码表（503 一行增加两个 code）、§7、§9.2 第 5 条 |
| R2-2 | 契约 §4.4 的 `candidatePool` 与 Java 现行池大小如何对应（§4-2） | Java 下发 `candidatePool = max(20, 2*topK)`；`topK ≤ 20` 时最大为 40，落在 `[topK, 50]` 范围内。Python 把它同时用作 dense、lexical、rrf 三路的 TopK。**Dense-only 路径**（`DENSE` 以及 `DENSE_WITH_BOUNDED_FALLBACK` 的第一步）的 dense TopK 固定为 **20**，不读 `candidatePool` | §4.4 |
| R2-3 | `MULTI_QUERY_HYBRID` 融合后的返回条数 `min(topK+3(n-1), 24)` 可能超过 `topK`（§4-3） | 为与现行行为等价，**保留** `min(topK + 3*(n-1), 24)`（n 为去掉空白、去重后的查询条数，含主查询）。请求约束仍为 `topK ∈ [1,20]`；**响应 `hits` 条数上限为 24**。只有 `MULTI_QUERY_HYBRID` 且 n ≥ 2 时 hits 会超过 `topK`，其他策略不超过 `topK` | §4.4 约束与响应 |
| R2-4 | `DENSE` 当前不会被路由产生（§4-4） | Python 仍要支持 `DENSE`，四个取值与语义一一对应。未知取值返回 400 | §4.4 |
| R2-5 | P2-R1 R1-5 要求 Java 先改平局规则再重跑 39 题；Java 现行检索命中不带 `chunk_no`，要改就得把 `chunk_no` 加进即将删除的旧链 | **推翻 R1-5 中"Java 先改"的做法**：<br>• 不改即将删除的旧检索链，`ReciprocalRankFusion`、`SemanticHeuristicReranker`、`RetrievalPipeline.executeMultiQuery`、`SentenceWindowExpander`、`NeuralModelReranker` 五处平局规则保持不变；<br>• 金标准夹具导出时，用 `document_chunk` 把每个命中的 chunk id 映射成 `(documentId, chunkNo)`，夹具里同时带分数（dense、lexical、rrf、rerank、final）；<br>• Python 按 `(documentId, chunkNo)` 升序打破平局；<br>• 逐题比对时，分数差 ≤ `1e-9` 的相邻候选视为同一平局组：组内按集合比较，不比顺序；组与组之间的顺序必须一致；<br>• 夹具导出器写成测试工具代码（`src/test`，不进 `src/main`），先写好、不运行；<br>• 39 题基线重跑与夹具导出由中心 agent 单独安排 Docker 窗口统一执行，Java 工作包不执行 | §9.1、§9.3 第 7 条、P2-R1 R1-5 |

### 12.5 P2-R3（中心 agent 于 2026-11-27 裁决，引用复核接入点与存量正文缺失）

本节优先级等同 §12.1–§12.4；与正文或之前的修订冲突时以本节为准。

| 编号 | 问题 | 裁决 | 影响正文 |
|---|---|---|---|
| R3-1 | §6 列出的 5 处复核点（`EvidenceVerificationService`、`KnowledgeCitationMapper`、`KnowledgeMessageMapper`、`AgentRepository:1158`、`KnowledgeCitationValidator`）读的是旧 Java 检索产出的 `KnowledgeSource` / `EvidenceRef` / `AgentCitation` / `SeedEvidenceInput`，这些 record 只带 `document_chunk.id`，没有 `chunkNo`、`bodySnapshotId`。现在就改要动约 24 个文件，而且改的是马上要删的代码 | **5 处调用点不在 P2-J 工作包改，挪到「切换」工作包**，与 `ProjectKnowledgeRetrieval` 接入 Python 检索、删除旧检索一起做。届时这几个 record 直接改成 `documentId + chunkNo + contentHash (+ bodySnapshotId)`，不保留 `chunkId`，也不设过渡期。<br>**复核器已就绪**：`retrieval/remote/KnowledgeHitVerifier`（含 `JdbcKnowledgeHitRepository`，提交 `388a3578`）按 §6 第 1–6 条实现，越权、过期快照、缺行、哈希不符、`chunkRefs` 不符、撤权竞态都已有单测；切换包直接使用 | §6、§8 |
| R3-2 | 存在 READY 但没有 `document_body` 的存量文档（`DocumentContentService` 的 `legacySource` 分支）。§5.5 的全量重建只读 `document_body_chunk`，覆盖不到它们 | **切换前必须补齐正文**，新增「切换前置」任务：<br>• 对 `status='READY'` 且没有 `document_body` 行的文档，用 `project_document.object_key` 取原始文件，走现有 Java 解析和切块管线（`DocumentParser` → `DocumentChunker` → `DocumentContentService.saveParsed`），生成 `document_body` 与 `document_body_chunk`；不写 `document_chunk`，不做向量化；<br>• 原始文件已不存在的，文档标记为 `FAILED`，`error_message = 'legacy_source_missing'`，数量写进切换报告；指向它们的旧引用接受失效；<br>• 任务必须幂等：已有 `document_body` 的文档跳过；<br>• 任务支持 dry-run：只统计、不写库，作为 §7 前置检查第 5 条的数据来源；<br>• 只读统计 SQL 如下，结果在 staging 实测，不估算：<br>`SELECT count(*) AS missing_body, count(*) FILTER (WHERE d.object_key IS NOT NULL AND d.object_key <> '') AS has_object_key FROM project_document d WHERE d.status='READY' AND NOT EXISTS (SELECT 1 FROM document_body b WHERE b.document_id=d.id);`<br>SQL 只能看到 `object_key` 有没有值，判断不了对象存储里文件还在不在。所以"原始文件仍在"的数量以 dry-run 对每个 `object_key` 做存在性检查的结果为准。<br>P2-J 工作包先写好这个任务并配单测，**不运行** | §5.5、§7 部署前置检查第 5 条 |
| R3-3 | 过渡期是否继续写 `knowledge_citation.chunk_id` | R3-1 选择一次性切换，**不存在过渡期**。§7 回滚条款不改：切换后新写入的引用 `chunk_id` 为空，回滚后这部分引用失效，已按 Q11 接受 | 无 |

### 12.6 P2-R4（中心 agent 于 2026-11-27 裁决，重建任务的项目归属与孤儿索引行兜底）

本节优先级等同 §12.1–§12.5；与正文或之前的修订冲突时以本节为准。

| 编号 | 问题 | 裁决 | 影响正文 |
|---|---|---|---|
| R4-1 | `REINDEX_GENERATION` 是全站任务，令牌可以不带 `pid`（R1-3），但 `ai_job.project_id` 是 `NOT NULL`（V91，V97 改为级联删除） | **直接修改 V105**（未推送、未在任何环境执行，staging 部署明确排除 V105/V106，不属于"修改已执行的历史迁移"）：<br>• `ALTER TABLE ai_job ALTER COLUMN project_id DROP NOT NULL`；<br>• 新增 `CHECK (project_id IS NOT NULL OR job_type = 'REINDEX_GENERATION')`：只有重建任务可以没有项目，其余类型（含 `MEMORY_EXTRACTION`）必须有；<br>• 索引类任务是系统任务，`created_by` 可为空，派发时按任务类型签发索引任务令牌（`uid` 缺省）；`MEMORY_EXTRACTION` 仍要求 `created_by`，行为不变 | §4.2、§5.4 |
| R4-2 | 项目删除会通过 `ai_job.project_id ON DELETE CASCADE` 删掉尚未完成的 `DELETE_DOCUMENT_INDEX`，`ai_index` 留下孤儿行 | **时间窗口经核实存在**：<br>• `ProjectMapper.countDocuments` 是 `SELECT count(*) FROM project_document WHERE project_id=?`，DELETING 状态的文档也计入，所以文档行还在时项目删不掉；<br>• 但文档行在 `DocumentDeletionCompletionService.purgeAndComplete`（对象存储删除之后）就被删除，这一步不等待 `DELETE_DOCUMENT_INDEX` 成功；<br>• 因此"文档行已删、删索引任务未完成"期间删除项目，任务会被级联删除。<br>**做法：数据库状态 + 补偿扫描兜底，不依赖任务一定跑完；项目删除接口不加新的阻塞，用户行为不变。** Java 补偿扫描定期（默认每 6 小时，带 Redisson 锁）对 ACTIVE 和 BUILDING generation 调用 `:reconcile` 的清理模式（下方 R4-3），下发"应被索引"的文档集合，Python 删除集合外的索引行并返回删除数。Java 记指标 `ai_index_orphan_rows_deleted_total`（Micrometer 名 `ai_index.orphan_rows_deleted`）。集合由 Java 计算，Python 不读业务 schema | §4.2、§4.6、§10 |
| R4-3 | `:reconcile` 需要支持清理，但"删除不在集合内的行"不能逐批直接做：分批下发时，每一批之外的文档都会被误删 | **Python 侧待办**，见下方 | §4.6 |

**Python 侧待办（R4-3，`POST /v1/index/generations/{id}:reconcile` 增加清理模式）：**

现有只读模式不变：请求体只有 `documentIds` 时，返回该 generation 下缺 `indexed_document` 记录的文档。

清理模式请求体：

```json
{ "mode": "PRUNE",
  "asOf": "2026-11-27T08:00:00.123456Z",
  "range": { "afterDocumentId": "uuid|null", "throughDocumentId": "uuid|null" },
  "documentIds": ["uuid", "..."] }
```

- **按 ID 区间分批，无状态**：Java 对应被索引的文档按 `id` 升序（PostgreSQL uuid 排序）切成每批 ≤ 5000 个，每批带上本批覆盖的区间 `(afterDocumentId, throughDocumentId]`。第一批 `afterDocumentId=null`，最后一批 `throughDocumentId=null`（表示到末尾），相邻批次区间首尾相接，合起来覆盖整个 uuid 空间。Python 只删 `document_id` 落在本批区间内、且不在 `documentIds` 里的文档。每批各自幂等，可以单独重试，Python 不需要保存会话状态。（R4-3 确认）最后一批 `throughDocumentId=null`，表示区间 `(afterDocumentId, ∞)`：Python 只删落在这个区间内、且不在本批 `documentIds` 里的文档；只有一批时两端都是 `null`，即整个空间。
- **防误删新上传**：`asOf` 是 Java 计算集合前取的数据库时间（`SELECT now()`，与 `ai_index` 同一 PostgreSQL 实例，没有时钟偏差）。Python 只删除 `indexed_document.indexed_at < asOf` 的文档，以及这些文档在 `ai_index.chunk` 里的行，`asOf` 之后写入的不动。（R4-3 确认）两边都必须用同一个 PostgreSQL 实例的服务器时间：Java 取 `SELECT now()`，Python 写 `indexed_document.indexed_at` 时用列的 `DEFAULT now()`（重新索引时同样写 `now()`），**不得使用任何一方应用进程的本地时钟**。
- **"应被索引"的集合**：`project_document.status IN ('UPLOADED','PARSING','INDEXING','READY')`。不只取 READY：INDEXING 文档的索引行在 result 被 Java 接收前就已经写入，只取 READY 会误删正在上传的文档。（R4-3 确认）DELETING、FAILED 以及行已删除的文档都不在集合里，它们的索引行会被删掉，**这是预期行为**：FAILED 文档不参与检索，重新上传或重新处理时会派发新的 `INDEX_DOCUMENT`。
- 作用范围：只处理路径上的这一个 generation。Java 的定期兜底通过 `GET /v1/index/generations` 找出 ACTIVE 和 BUILDING，逐个调用；RETIRED 只在回滚重新激活前由人工流程调用（§7），所以 PRUNE 必须也接受 RETIRED generation。
- 响应 `200`：`{ "deletedDocuments": 3, "deletedRows": 412 }`。generation 不存在返回 404；`range` 不合法（after ≥ through）或 `documentIds` 不在区间内返回 400。
- scope 仍为 `index:admin`，幂等。

遗留问题（已确认，R4-4）：重新激活 RETIRED generation 回滚时，这个 generation 有两处和现状不一致：退役期间被删除的文档在它里面还有行；退役之后新上传的文档在它里面没有索引。所以激活前先 PRUNE、再 reconcile 补派，两步都完成后才能 `:activate`，步骤写在 §7 回滚。在多余的行被清理之前，§6 的复核会先把它们丢弃（第 1、2 条），不会造成越权。

**R4-4 待裁决（与 §4.2 冲突，中心 agent 定）**：§4.2 规定带 `generationId` 的 `INDEX_DOCUMENT` 在 generation 不是 `BUILDING`/`ACTIVE` 时上报 `INDEX_GENERATION_STALE`，所以对 RETIRED generation 补派的子任务都会失败；§4.6 的 `:activate` 也只写了 `BUILDING → ACTIVE`。建议 Python 新增 `POST /v1/index/generations/{id}:reopen`：`RETIRED → BUILDING`，已有其他 BUILDING 时返回 409，已是 BUILDING 时返回 200。回滚流程改为 reopen → PRUNE → reconcile 补派 → 子任务全部成功 → activate，完全复用正常重建的路径；reopen 之后常规上传会同时写 ACTIVE 和这个 BUILDING，补派期间也不会漏掉新文档。在裁决之前，Java 侧不实现回滚重新激活。
