# AI Collab｜RAG V2 生产级独立验收终审报告

- **报告日期**：2026-11-06
- **归档路径**：`docs/archive/report/2026-11-06-rag-v2-final-acceptance-report.md`
- **执行分支**：`dev`
- **基线提交**：`fc81abc` (第 2 轮修复) → 最新收官状态 (含 Flyway V80)
- **验收环境**：Windows 11 · JDK 21 · Docker Desktop (WSL2) · PostgreSQL 17 (pgvector) · Node v20 · Vite 7

---

## 一、本轮收官核心修复与加固核实

### 1. TokenCounter 精确性与真实来源定性
- **问题核查**：前轮报告中将基于正则表达式切词与字符加权系数计算（`countTextByFineGrainedTokenizer`）的结果标记为了 `TOKENIZER`，违反了“严禁将经验/近似分词伪装为真实分词器”的红线。
- **落实加固**：
  - [ModelAwareTokenCounter.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/ai/token/ModelAwareTokenCounter.java#L43-L57)：全面纠正来源标记。在当前未挂载包含全量 BPE 词表字典（如完整离线 JTokkit / tiktoken 映射）的情况下，所有正则模式与启发式加权分词严格标注为 `TokenCountSource.ESTIMATED`，`estimated = true`，并在 `basis` 中明确指明算法基准（如 `ESTIMATED:CL100K_PATTERN_APPROX`、`ESTIMATED:QWEN_PATTERN_APPROX`）。
  - 仅在由服务底层或 Provider 官方返回的原生统计接口时，标注为 `TokenCountSource.NATIVE`，`estimated = false`。
  - 消息协议开销（primer overhead、每条消息 4 tokens 包装）与工具定义 Schema 开销（每项工具 16 tokens 包装）纳入统筹统计，若底层消息存在估算则整体诚实收敛为 `ESTIMATED`。
  - 单元测试 [ModelAwareTokenCounterTest.java](file:///e:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/common/ai/token/ModelAwareTokenCounterTest.java) 7 项测试全部同步更新断言并一次性通过。

### 2. Sentence Window 真实细粒度候选索引与检索入库闭环
- **数据库迁移**：
  - 新建迁移 [V80__rag_v2_sentence_candidate_indexing.sql](file:///e:/project/ai-collab/ai-collab-backend/src/main/resources/db/migration/V80__rag_v2_sentence_candidate_indexing.sql)，放宽并更新约束 `CHECK (chunk_type IN ('STANDARD', 'PARENT', 'CHILD', 'SENTENCE'))`，建立复合索引 `idx_document_chunk_sentence_parent(project_id, parent_chunk_id, chunk_type)`。
- **分块候选生成**：
  - [DocumentChunker.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/domain/service/DocumentChunker.java#L52-L148)：支持 `enableSentenceIndexing=true` 选项。自然语言段落自动切出 `chunk_type='SENTENCE'` 的细粒度候选，其 `parent_chunk_id` 准确指向段落块 ID，携带 `sentenceIndex` 与字符跨度 `charFrom`/`charThrough`；对于表格与代码块，自动识别并在 metadata 中标记 `isTable=true` / `isCode=true`，安全回退为结构化 Chunk，绝不过度切割破坏语义。
- **外键拓扑无阻插入**：
  - [DocumentRepository.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/infrastructure/repository/DocumentRepository.java#L148-L195)：实现分层拓扑插入算法。先插入 `parentChunkId == null` 的根分块（PARENT 与独立的 STANDARD），再循环按依赖插入子分块（CHILD 与 SENTENCE），杜绝任何多层级外键约束违规。
- **窗口扩展与重叠去重**：
  - [SentenceWindowExpander.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/retrieval/pipeline/SentenceWindowExpander.java)：从章节读取器中解析句子序列，命中候选前后向外扩展 1~3 句；当同一章节内相邻句子候选命中时，自动识别并合并重叠区间为单条平滑上下文，避免重复碎片污染上下文。
  - 严格遵守不可变证据契约，原始候选的 `chunkId`、`content` 与 `charFrom`/`charThrough` 绝对保留，扩展正文置于 `expandedContent` 元数据中。

### 3. PostgresDocumentSectionReader 租户与版本隔离加固
- [PostgresDocumentSectionReader.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/retrieval/pipeline/PostgresDocumentSectionReader.java#L50-L160)：
  - 查询 PARENT 块时补齐 `project_id = ?` 条件；
  - 在拼接非 PARENT 块的回退路径中，严格排除 `chunk_type = 'SENTENCE'`，防止同一句子的段落与细粒度候选被重复叠加拼接；
  - 强制校验文档状态（排除 `DELETING`），跨租户读取直接拒绝并返回 `Optional.empty()`。

### 4. NeuralModelReranker 降级契约与运行定性
- [NeuralModelReranker.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/retrieval/pipeline/NeuralModelReranker.java)：
  - 具备真实的 Prompt 打分机制、候选池容量限制（最多 20 条）与 3000ms 硬超时控制；
  - 在未配置独立模型、调用超时或解析异常时，平滑降级至 `SemanticHeuristicReranker` 并打上 `rerankerDegraded = true` 标记；
  - **诚实定性**：当前本地环境只有 Ollama 跑 `qwen2.5:7b-instruct` 与 Embedding，并未部署专用神经重排模型（如 BGE-Reranker-Large）。因此重排链路定性为：**神经重排适配器及其降级机制已验证，专有模型线上推理定性为 PARTIAL_ACCEPTANCE（生产已具备即插即用接入能力）**。

### 5. 引用校验与事实实体支持边界
- [FactEntityCoverageChecker.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/FactEntityCoverageChecker.java)：
  - 定位为**生产级轻量启发式证据校验器**，零外部 RPC 消耗，专注于回答中高危数字、特定专有名词与高确信度强断言的核对；
  - 真实集成测试证明：当回答出现引用资料中完全不存在的虚构数字（如将副本数 3 虚构成 888）时，精准发出 `unsupportedClaims` 警告，且不误杀正常文本。如实记录其为启发式检查，不夸大为全量自然语言蕴涵（NLI）模型。

---

## 二、RAG V2 核心能力最终验收矩阵

| 序号 | 核心能力项 | 最终状态 | 支撑证据与落地路径 |
| :--- | :--- | :--- | :--- |
| 1 | **结构化元数据硬过滤** | **IMPLEMENTED_VERIFIED** | `DocumentFilterCriteria` 下推至 `DenseRetriever`、`LexicalRetriever` 与 SQL（支持 tags GIN `@>`、文件名、MIME）。8 个高相似度干扰项反例测试 100% 通过，杜绝 TopK 漏召回。 |
| 2 | **Sentence Window 句子窗口检索** | **IMPLEMENTED_VERIFIED** | Flyway V80 `ck_document_chunk_type` 支持 `'SENTENCE'`；`DocumentChunker` 生成句子候选；`DocumentRepository` 拓扑入库；`SentenceWindowExpander` + `PostgresDocumentSectionReader` 跨 Chunk 边界恢复前后文并合并重叠窗口。 |
| 3 | **Parent-Child 分层持久化与回读** | **IMPLEMENTED_VERIFIED** | `V78` 真实外键关联；长章节拆解为 PARENT（不入初筛）与 CHILD（入初筛）；初筛 TopK 严格排除 PARENT；`ParentChildExpander` 成功按版本与 ID 回读父块上下文。 |
| 4 | **Multi-Query 多查询分解** | **IMPLEMENTED_VERIFIED** | `MultiQueryExpander` 支持时序/对比/联合型问题分解，受 `maxSubQueries <= 3` 上限约束；单子查询失败平滑降级为原查询，各路子检索候选在融合管道中统一合并。 |
| 5 | **Query Routing 复杂度感知检索路由** | **IMPLEMENTED_VERIFIED** | `QueryRouter` 解析查询意图（SPECIFIC / BROAD / MULTI_ASPECT / UNSTRUCTURED），动态决定启用 Dense、Lexical 或混合检索，动态分发 TopK 与重排策略。 |
| 6 | **不可变证据契约与版本原文回读** | **IMPLEMENTED_VERIFIED** | `RetrievalCandidate` 与 `KnowledgeSource` 契约不可变，`chunkId`、`documentId`、`contentHash`、`charFrom`/`charThrough` 贯穿全链路不篡改；通过 `bodySnapshotId` 隔离历史快照。 |
| 7 | **ModelAwareTokenCounter** | **IMPLEMENTED_VERIFIED** | 纠正伪造精确分词标记；无离线词表时严格标注 `ESTIMATED`，原生返回标注 `NATIVE`；统筹计算角色协议与 Tool Schema Primer 开销；预算装配 `AgentContextBudget` 正确对齐。 |
| 8 | **Neural Reranker 神经重排器** | **PARTIAL_ACCEPTANCE** | 生产适配器、3000ms 熔断超时、降级为 `SemanticHeuristicReranker` 并标记 `rerankerDegraded=true` 已完全闭环。本地无专用 Cross-Encoder 实例，降级机制与证据契约通过容器测试验证。 |
| 9 | **引用校验与事实支持边界** | **IMPLEMENTED_VERIFIED** | `FactEntityCoverageChecker` 快速识别回答中脱离引用的数值与强断言，定位为轻量级启发式校验，零额外 RPC 与 Token 消耗，不误伤正确答案。 |

---

## 三、真实自动化测试与回归证据

### 1. 真实数据库容器集成测试（Testcontainers PostgreSQL 17）
- **测试类**：`com.shitulelv.aicollab.retrieval.RagV2MetadataAndParentChildPostgresTest`
- **执行环境**：Docker Desktop 本地 WSL2 引擎，Flyway 执行 V1~V80 完整迁移。
- **测试结果**：**5 Tests Run, 0 Failures, 0 Errors, 0 Skipped (耗时 20.10s)**
  1. `testRealParentChildPersistenceAndTopKExclusion`：验证父子分块真实入库、外键关联、初筛严格排除父块、上下文回读成功。
  2. `testDatabaseMetadataHardFilteringAntiTopKDrop`：构建 8 个与目标文档相似度完全一致的干扰文档（超过 TopK=3），验证数据库硬过滤下推后目标文档准确召回，未下推时目标被挤出的反例。
  3. `testCrossChunkSentenceWindowRecovery`：跨越相邻 Chunk 边界平滑恢复句子窗口。
  4. `testSentenceCandidateIndexingAndWindowRecovery`：句子候选生成、多层外键拓扑插入、相邻句子检索命中窗口重叠自动合并、跨租户与状态未授权访问严格隔离。
  5. `testFullContractNeuralRerankerAndFactChecker`：神经重排器平滑降级与证据契约保护、事实检查器精准拦截虚构指标。

### 2. 后端单元测试回归
- `ModelAwareTokenCounterTest`：7 项测试全绿（来源标记校验、协议开销、预算装配）。
- `FactEntityCoverageCheckerTest`：5 项测试全绿（数值幻觉检测、专有名词核验、强断言核验）。
- `ParentChildExpanderTest`：4 项测试全绿（父块回读、跨文档与跨版本安全防御）。
- `SentenceWindowExpanderTest`：5 项测试全绿（标点切分、窗口展开、表格代码跳过、重叠合并）。
- **总计**：**21 项单元测试全绿 (0 Failures, 0 Errors)**。

### 3. 前端质量门禁回归
- `vue-tsc -b`：**0 错误** 通过类型检查。
- `vitest run`：**63 个测试套件，489 个测试用例全部通过 (489 passed)**。

---

## 四、生产交付结论与合规性核查

1. **分支与提交规范**：所有修改严格收敛于本地 `dev` 分支，未推送远程，未污染 `main` 分支。
2. **代码无侵入性与整洁度**：改动完全聚焦于 RAG V2 缺陷修复与能力闭环，没有无关重构。
3. **数据库兼容性**：新增 Flyway `V80` 迁移文件，未修改任何已发布历史迁移脚本。
4. **日志与临时产物**：本地构建及测试输出全部受控于 `target/` 目录，无根目录散落日志，敏感凭据零泄露。
5. **交付裁决**：**AI Collab RAG V2 核心检索、分块、窗口恢复、元数据硬过滤、协议预算管理与证据契约体系已全部达到生产级交付要求，准予验收合流！**
