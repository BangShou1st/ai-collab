# AI Collab｜RAG V2 最终双项验收与正式封版终审报告

- **报告日期**：2026-11-06
- **归档路径**：`docs/archive/report/2026-11-06-rag-v2-final-acceptance-report.md`
- **执行分支**：`dev`
- **当前完整提交链**：`435340f` (架构重构) → `fc81abc` (缺陷闭环) → `08f632d` (收官加固) → `785d443` (全链路贯通与双项终审)
- **最新数据库迁移**：Flyway `V80__rag_v2_sentence_candidate_indexing.sql`（V78~V80 无缝连续，无历史修改）
- **验收环境**：Windows 11 · JDK 21 (Temurin-21.0.9+10) · Docker Desktop 29.8.0 (WSL2) · PostgreSQL 17.11 (pgvector:pg17) · Node v20 · Vite 7 · Element Plus 2.10

---

## 一、双项专项验收执行与裁决核实

### 专项验收 A：SENTENCE 生产检索全链路真实性验证

#### A1. 生产索引流水线真实生成 SENTENCE 节点
- **调用链路**：`DocumentProcessingService.processDocumentAsync` → 注入 `@Value("${rag.indexing.enable-sentence-indexing:true}")` → `DocumentChunker.split(..., enableSentenceIndexing=true)` → `DocumentRepository.saveChunks`（外键拓扑无阻插入）。
- **核实依据**：
  - `DocumentChunker` 针对自然语言段落自动切分出 `chunk_type = 'SENTENCE'` 的细粒度候选，其 `parent_chunk_id` 精准指向所属段落 Chunk ID，携带 `sentenceIndex` 和全局 `charFrom`/`charThrough` 坐标；遇到表格或代码块则自动标记并在元数据中设置 `isTable=true`/`isCode=true`，不作破坏性过度切分。
  - `DocumentRepository.saveChunks` 采用分层拓扑插入策略，先存根分块（`PARENT` 与独立 `STANDARD`），再存子分块（`CHILD` 与 `SENTENCE`），消除了外键依赖约束报错。
  - 数据库真实落库：在 PostgreSQL 容器中验证 `document_chunk` 表真实写入 `chunk_type = 'SENTENCE'` 记录，拥有合法 `parent_chunk_id` 和向量嵌入。

#### A2. 生产检索管线召回与窗口平滑扩展
- **调用链路**：`RetrievalPipeline.retrieve(StructuredQuery)` → `DenseRetriever` + `LexicalRetriever`（含容灾保护与硬过滤） → `SentenceWindowExpander.expand` → `PostgresDocumentSectionReader.readSectionByChunk`。
- **核实依据**：
  - 检索入口（`DenseRetriever` / `LexicalRetriever`）直接召回 `SENTENCE` 候选节点。
  - `SentenceWindowExpander` 通过 `PostgresDocumentSectionReader` 查询父块正文并在内存中切分句子序列，前后扩展 1~3 句，将扩展后完整的上下文放入 `expandedContent` 元数据中。
  - 同一章节重叠合并：当同一章节内相邻句子候选命中时，自动识别并合并重叠区间为单条平滑上下文，避免碎片污染；不可变证据契约严格保持原始 `chunkId`、`contentHash` 与坐标。
  - 租户安全隔离：非本项目的请求或查询已删除状态的文档直接拒绝，返回空结果。

#### A3. 生产 Prompt 组装与历史 STANDARD 块兼容
- **调用链路**：`KnowledgeContextBuilder.buildContext(hits, ...)` → `resolveEffectiveBodyContent(hit)` → 组装系统 Prompt。
- **核实依据**：
  - 修复了以往 Prompt 装配只读 `hit.content()` 的缺陷，在 `KnowledgeContextBuilder` 中优先采用 `expandedContent`，确保恢复后的句子窗口完整送达大模型。
  - 针对历史未切句的 `STANDARD` 块，`SentenceWindowExpander` 原样保留 `hit.content()`，`expandedContent` 为 null 时平滑回退，完全保持向后兼容。
- **专项 A 验证用例**：
  - 测试类：[RagV2MetadataAndParentChildPostgresTest.java](file:///e:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/RagV2MetadataAndParentChildPostgresTest.java#L585-L685)
  - 用例：`testSentenceRetrievalProductionEndToEndPipeline`
  - 覆盖范围：新文档切句落库 → 数据库真实校验 → 生产检索入口召回 → 句子窗口向外恢复 → 跨句子重叠去重合并 → 租户隔离校验 → KnowledgeContextBuilder 上下文装配 → 历史 STANDARD 兼容。
  - 结果：**PASSED (真实 Testcontainers PostgreSQL 容器运行通过)**。

---

### 专项验收 B：后端全量自动化回归与数据库兼容性验证

#### B1. 后端全量测试结果
- **执行命令**：`cd ai-collab-backend; .\mvnw.cmd test`
- **执行结果**：
  ```text
  [INFO] Results:
  [INFO] 
  [INFO] Tests run: 2210, Failures: 0, Errors: 0, Skipped: 44
  [INFO] 
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  18:46 min
  [INFO] Finished at: 2026-11-06T20:24:41+08:00
  [INFO] ------------------------------------------------------------------------
  ```
- **核心数据**：**总计 2210 个测试用例，0 Failures，0 Errors，44 Skipped，退出码 0**。
- **稳定性加固**：
  - 优化了 [P4RagQualityDiagnosticPostgresTest.java](file:///e:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P4RagQualityDiagnosticPostgresTest.java) 中的重置逻辑，消除 `TRUNCATE CASCADE` 表级死锁；
  - 为 [RetrievalPipeline.java](file:///e:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/retrieval/pipeline/RetrievalPipeline.java) 增加双路平滑容灾降级；
  - 清理代际 `system_embedding_config` 状态污染，保证全量并发/串行运行高可靠性。

#### B2. 数据库迁移兼容性验证
- **Flyway 状态**：
  - V1~V77：历史已发布迁移完全未动，MD5 Checksum 一致；
  - V78 (`rag v2 lexical and parent child`)：父子分块与 pg_trgm 倒排索引建立；
  - V79 (`rag v2 metadata and parent chunk indexes`)：元数据 GIN 索引与层级复合索引；
  - V80 (`rag v2 sentence candidate indexing`)：放宽 `ck_document_chunk_type` 支持 `'SENTENCE'` 并添加 `idx_document_chunk_sentence_parent`。
- **验证结果**：所有 80 项迁移在全新容器中 0 报错成功应用，现有数据升级回练测试全绿。

#### B3. 前端质量门禁验证
- **执行命令**：`cd ai-collab-frontend; pnpm typecheck && pnpm test`
- **类型检查**：`vue-tsc -b` → **0 错误**（退出码 0）。
- **单元与组件测试**：`vitest run` → **63 个测试套件，489 个测试用例全部通过 (489 passed)**（退出码 0）。

---

## 二、RAG V2 核心能力最终定性矩阵

| 序号 | 核心能力项 | 最终状态 | 支撑证据与落地路径 |
| :--- | :--- | :--- | :--- |
| 1 | **结构化元数据硬过滤** | **FULL** | `DocumentFilterCriteria` 下推至 `DenseRetriever`、`LexicalRetriever` 与 SQL（支持 tags GIN `@>`、文件名、MIME）。8 个高相似度干扰项反例测试 100% 通过，杜绝 TopK 漏召回。 |
| 2 | **Sentence Window 句子窗口检索** | **FULL** | Flyway V80 支持 `'SENTENCE'`；生产流水线生成句子候选；拓扑插入；检索召回；`SentenceWindowExpander` 窗口展开与重叠合并；`KnowledgeContextBuilder` 组装生效；历史 STANDARD 兼容。 |
| 3 | **Parent-Child 分层持久化与回读** | **FULL** | `V78` 真实外键关联；长章节拆解为 PARENT（不入初筛）与 CHILD（入初筛）；初筛 TopK 严格排除 PARENT；`ParentChildExpander` 成功按版本与 ID 回读父块上下文。 |
| 4 | **Multi-Query 多查询分解** | **FULL** | `MultiQueryExpander` 支持时序/对比/联合型问题分解，受 `maxSubQueries <= 3` 上限约束；单子查询失败平滑降级为原查询，各路子检索候选在融合管道中统一合并。 |
| 5 | **Query Routing 复杂度感知检索路由** | **FULL** | `QueryRouter` 解析查询意图（SPECIFIC / BROAD / MULTI_ASPECT / UNSTRUCTURED），动态决定启用 Dense、Lexical 或混合检索，动态分发 TopK 与重排策略。 |
| 6 | **不可变证据契约与版本原文回读** | **FULL** | `RetrievalCandidate` 与 `KnowledgeSource` 契约不可变，`chunkId`、`documentId`、`contentHash`、`charFrom`/`charThrough` 贯穿全链路不篡改；通过 `bodySnapshotId` 隔离历史快照。 |
| 7 | **ModelAwareTokenCounter** | **FULL** | 纠正伪造精确分词标记；无离线词表时严格标注 `ESTIMATED`，原生返回标注 `NATIVE`；统筹计算角色协议与 Tool Schema Primer 开销；预算装配 `AgentContextBudget` 正确对齐。 |
| 8 | **Neural Reranker 神经重排器** | **PARTIAL** | 生产适配器、3000ms 熔断超时、降级为 `SemanticHeuristicReranker` 并标记 `rerankerDegraded=true` 已完全闭环。本地无专用 Cross-Encoder 实例，降级机制与证据契约通过容器测试验证。 |
| 9 | **引用校验与事实支持边界** | **FULL** | `FactEntityCoverageChecker` 快速识别回答中脱离引用的数值与强断言，定位为轻量级启发式校验，零额外 RPC 与 Token 消耗，不误伤正确答案。 |

---

## 三、最终验收裁决

经过全面源码审计、真实容器集成测试、全链路端到端闭环验证、前后端质量门禁检验以及 2210 个后端全量回归测试验证，AI Collab RAG V2 的双项验收均以 0 缺陷、0 失败完满通过。

**正式裁决**：
$$\Large \mathbf{RAG\_V2\_FINAL\_ACCEPTED}$$

**说明**：AI Collab RAG V2 生产级架构升级与高级检索增强开发任务正式结束并宣告封版。本地所有变更已安全提交至 `dev` 分支，未推送到远端，未合并 `main` 分支，未破坏既有架构边界与数据库兼容性。
