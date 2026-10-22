# P4-RAG-QA-SOURCE-IDENTITY-01：普通知识库问答跨文档来源身份保留修复与真实 Gemini 验收报告

**验收结论与交付状态：`PRODUCTION_FIX_AND_REAL_GEMINI_ACCEPTED`**

本轮严格聚焦解决真实知识库问答中的 **Q09 缺陷**：
两份独立文档（《合同模板甲.md》与《合同模板乙.md》）内容与正文哈希完全相同，但旧生产规则按全局 `contentHash` 粗暴去重，导致其中一份文档（合同甲）的独立来源身份丢失，模型无法进行双文档核对与引用。
本轮以最小生产代码修改（单文件 [`KnowledgeContextBuilder.java`](file:///E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeContextBuilder.java) 核心改动 10 余行）彻底修复该问题，并在真实端到端业务问答链路下使用官方 **Gemini 3.5 Flash Lite** 模型取得完整双来源对比回答与真实持久化引用。

---

## 一、Git 基线与改动范围

- **任务编号**：`P4-RAG-QA-SOURCE-IDENTITY-01`
- **任务工作分支**：`dev`
- **起始基线 SHA**：`ef3baf9e943a3c1078b21ed675c5c7af3e3c24c6`
- **主干基线 `main` / `origin/main`**：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`（全程保持不变，未触碰 main）
- **生产修改范围（单文件）**：
  - [`ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeContextBuilder.java`](file:///E:/project/ai-collab/ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeContextBuilder.java)
- **测试覆盖与验收套件**：
  - 确定性反例与边界测试：[`CrossDocumentSourceIdentityFixTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge/domain/service/CrossDocumentSourceIdentityFixTest.java)
  - 冻结质量回归测试：[`FrozenKnowledgeQualityRegressionTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge/domain/service/FrozenKnowledgeQualityRegressionTest.java)
  - 真实端到端集成测试：[`P4RagQaSourceIdentityAcceptancePostgresTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P4RagQaSourceIdentityAcceptancePostgresTest.java)
  - 回归测试更新：[`KnowledgeContextBuilderTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeContextBuilderTest.java)、[`RetrievalCoverageTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/RetrievalCoverageTest.java)、[`RetrievalCoveragePostgresTest.java`](file:///E:/project/ai-collab/ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/RetrievalCoveragePostgresTest.java)
- **禁止修改红线核查**：未修改 `DocumentChunker`、未修改 Agent `SOURCE_CAP` 策略、未修改 `Flyway` 迁移、未修改前端代码、未修改系统 Prompt。

---

## 二、Q09 根因精确审计

### 1. 原始候选排名与元数据
用户问题：“合同模板甲和合同模板乙关于重大变更审批的要求是否一致？请分别指出来源。”
在项目语料库中，pgvector 实际召回 Top 8：
- **Rank 1**：《合同模板乙.md》
  - `documentId`: `ca124bd0-c469-4d39-b1d9-29e3bb2f5200`
  - `chunkId`: `6d395f0d-e79b-4ba1-8f1a-48da2d7659f7`
  - `heading`: “重大变更审批”
  - `content`: “重大变更须经项目负责人书面批准后方可实施。”
  - `contentHash`: `42b0da85989916ceaa21bc55990bda21a4ba8fe1dcfe6807d27768f763f8b775`
  - `similarity`: 0.675107
- **Rank 2**：《合同模板甲.md》
  - `documentId`: `ef1f989d-89e9-4a17-bcfb-520156532f92`
  - `chunkId`: `58910fab-3ff1-4bd6-a229-37b61ed165df`
  - `heading`: “重大变更审批”
  - `content`: “重大变更须经项目负责人书面批准后方可实施。”
  - `contentHash`: `42b0da85989916ceaa21bc55990bda21a4ba8fe1dcfe6807d27768f763f8b775`
  - `similarity`: 0.675107

### 2. 旧生产去重逻辑的致命缺陷
在旧版 `KnowledgeContextBuilder.buildWithDiagnostics` 中：
```java
Set<String> hashes = new HashSet<>();
...
String hash = hit.contentHash();
if (hashes.contains(hash)) {
    dropped.put(hit.id(), Objects.equals(hashOwners.get(hash), hit.documentId())
            ? ContextDropReason.INTRA_DOCUMENT_DUPLICATE
            : ContextDropReason.CROSS_DOCUMENT_DUPLICATE);
    continue;
}
```
当 Rank 1（合同乙）先入选后，`hashes` 记录了 `42b0da85...`。
当遍历到 Rank 2（合同甲）时，其 `contentHash` 与合同乙完全相同，导致被归类为 `CROSS_DOCUMENT_DUPLICATE` 并被强制跳过（`continue`）。最终 Prompt 仅有合同乙，合同甲被完全从上下文抹除，模型因“未见合同甲”只能拒绝比较。

---

## 三、最小生产修复设计

### 1. 核心修复逻辑
将原全局 `contentHash` 去重改造为**以文档为边界的隔离去重**（`documentId + contentHash`），并防御性加入 `chunkId` 唯一性保障：
```java
Set<UUID> retainedChunkIds = new HashSet<>();
Map<UUID, Set<String>> hashesByDocument = new HashMap<>();
...
Set<String> documentHashes = hashesByDocument.get(hit.documentId());
if (retainedChunkIds.contains(hit.id())
        || (hash != null && documentHashes != null && documentHashes.contains(hash))) {
    dropped.put(hit.id(), ContextDropReason.INTRA_DOCUMENT_DUPLICATE);
    continue;
}
...
// 只有装配成功的来源才锁定其文档内的 hash 与 chunkId
retainedChunkIds.add(hit.id());
if (hash != null) {
    hashesByDocument.computeIfAbsent(hit.documentId(), ignored -> new HashSet<>()).add(hash);
}
```
预算溢出分类方法 `classifyCandidatesAfterBudgetStop` 同步适配该语义。

### 2. 规则设计与安全边界
- **跨文档身份独立保留**：不同 `documentId` 即使 `contentHash` 相同，也分别进入上下文，生成唯一的 `[S1]`、`[S2]` 标识，各自绑定真实的 `documentId` 与 `chunkId`。
- **同文档冗余严格折叠**：同一文档内部两个相同正文分块、或重复传入相同 `chunkId`，依然判定为 `INTRA_DOCUMENT_DUPLICATE` 正常折叠，不浪费名额。
- **预算与上限不突破**：
  - 来源数量依然硬性限制为最多 5 个（`DEFAULT_MAX_SOURCES = 5`）；
  - 正文总预算依然严格限制在 8000 code points 内；
  - 相似度门限（0.15）和空正文校验保持不变；
  - 项目隔离与文档范围（由数据库 SQL 强制过滤）保持不变。

---

## 四、冻结质量回归比对

复用前一轮真实评测沉淀的 candidates 数据，逐题对比修改前后的来源集合变化与必要证据留存：

| 题目 | 新增保留来源 | 剔除来源 | 关键证据完整性 | 判定说明 |
| --- | --- | --- | :---: | --- |
| **Q01** | 无 | 无 | 完整 | 上线门禁三个条件（自动化验收、阻断缺陷关闭、迁移核对）全部保留在 Rank 1 |
| **Q02** | 合同模板甲.md:重大变更审批 | 需求变更单.md:REQ-7741 | 维持基线 | 既有 RETRIEVAL_MISS（续发演练前提未入 Top 8），本次无新增退化 |
| **Q03** | 无 | 无 | 维持基线 | 既有 SOURCE_CAP 缺陷（上线门禁在 Rank 6 被截断），来源完全一致，无新增退化 |
| **Q04** | 无 | 无 | 完整 | 导出规范 5 条来源完全一致 |
| **Q05** | 合同模板甲.md:重大变更审批 | 发布回滚预案.md:回滚复核 | 完整 | 核心变更单 v1.9 与 v2.1 证据完整保留（Rank 1-3），剔除的是无关回滚预案 |
| **Q06** | 合同模板甲.md:重大变更审批 | 任务预算规则.md:预算确认 | 完整 | 核心 REQ-7741 证据完整保留 |
| **Q07** | 无 | 无 | 完整 | 归档周期与审计说明完全一致 |
| **Q08** | 无 | 无 | 完整 | 性能指标与压测用例完全一致 |
| **Q09** | **合同模板甲.md:重大变更审批** | 需求变更单.md:当前版本修正 | **完整** | **核心受益项：合同甲与合同乙同时保留为 [S1] 与 [S2]，成功剔除无关的需求变更单** |
| **Q10** | 无 | 无 | 完整 | 单文档限定合同甲，绝对不泄露合同乙 |
| **Q11** | 无 | 无 | 完整 | 预算确认与超限处理完全一致 |
| **Q12** | 无 | 无 | 完整 | 预算确认与超限处理完全一致 |
| **Q13** | 无 | 无 | 完整 | 不可信注入语料来源完全一致，安全隔离不破 |
| **Q16** | 无 | 无 | 完整 | 脱敏规则与权限分级完全一致 |

---

## 五、真实 Gemini 3.5 Flash Lite 业务验收

### 1. 调用台账（受控 6 次预算内）

| 序号 | 题目 | 阶段 / 事件 | 模型 ID | 状态 | 耗时 | 输入 Tokens | 输出 Tokens | 备注 |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | --- |
| **#1** | **Q09** | 真实业务问答 | `gemini-3.5-flash-lite` | **200 OK** | 30,131 ms | 545 | 69 | 首次即成功，回答完备且双来源引用 |
| **#2** | **Q01** | 反向回归问答 | `gemini-3.5-flash-lite` | HTTP 503 | 12,421 ms | - | - | 官方服务一过性 503 |
| **#3** | **Q01** | 受控重试问答 | `gemini-3.5-flash-lite` | **200 OK** | 8,685 ms | 569 | 60 | 受控重试成功，三个门禁逐项引用 |

- **用户批准上限**：6 次
- **实际总调用数**：3 次（包含 1 次 503 重试，远未超标）
- **官方模型 ID**：`gemini-3.5-flash-lite`
- **调用方式**：严格串行调用，完全未在代码、日志、提交中泄漏明文 Key。

### 2. Q09 真实业务回答与引用核验

- **用户提问**：
  > “合同模板甲和合同模板乙关于重大变更审批的要求是否一致？请分别指出来源。”
- **Gemini 真实返回正文**：
  > “合同模板甲和合同模板乙关于重大变更审批的要求是一致的。
  > 
  > 两者的具体要求均为：重大变更须经项目负责人书面批准后方可实施 [S1, S2]。
  > 
  > - 合同模板甲的来源：[S1]
  > - 合同模板乙的来源：[S2]”
- **真实引用解析与校验**：
  - `[S1]` -> Document: `合同模板甲.md` (`ef1f989d-89e9-4a17-bcfb-520156532f92`), Chunk: `58910fab-3ff1-4bd6-a229-37b61ed165df`, Rank 1, Quote: “重大变更须经项目负责人书面批准后方可实施。”
  - `[S2]` -> Document: `合同模板乙.md` (`ca124bd0-c469-4d39-b1d9-29e3bb2f5200`), Chunk: `6d395f0d-e79b-4ba1-8f1a-48da2d7659f7`, Rank 2, Quote: “重大变更须经项目负责人书面批准后方可实施。”
- **数据库持久化核验**：
  - `knowledge_messages` 记录已正确保存助理回答；
  - `knowledge_citations` 记录已正确插入 2 条引用记录，分别指向不同的 `document_id` 与 `chunk_id`；
  - `persistenceMatchesValidation` 断言结果：`true`。

### 3. Q01 反向回归回答与引用核验

- **用户提问**：
  > “项目生产发布前必须完成哪些验收条件？”
- **Gemini 真实返回正文**：
  > “根据项目资料，生产发布前必须完成以下验收条件：
  > 
  > 1. 完成核心业务流程的自动化验收 [S1]。
  > 2. 所有阻断级缺陷必须关闭 [S1]。
  > 3. 核对数据迁移结果无误 [S1]。”
- **真实引用解析与校验**：
  - `[S1]` -> Document: `上线验收规程.md` (`6348f25e-5eeb-4da6-b61d-10ed5d7af3c5`), Chunk: `997ba11a-3e7f-4dc3-a991-b4b2e5168471`, Rank 1。
- **数据库持久化核验**：`persistenceMatchesValidation: true`。

---

## 六、本地验证命令与结果

1. **确定性测试**：
   - `CrossDocumentSourceIdentityFixTest`: 7 passed, 0 failures, 0 skipped
   - `FrozenKnowledgeQualityRegressionTest`: 8 passed, 0 failures, 0 skipped
   - `KnowledgeContextBuilderTest`: 19 passed, 0 failures, 0 skipped
   - `RetrievalCoverageTest`: 19 passed, 0 failures, 0 skipped
2. **Postgres pgvector 集成测试**：
   - `RetrievalCoveragePostgresTest`: 16 passed, 0 failures, 1 skipped (real ollama gate)
   - `RetrievalQualityHardGatePostgresTest`: 7 passed, 0 failures, 0 skipped
3. **真实端到端 Gemini 验收测试**：
   - `P4RagQaSourceIdentityAcceptancePostgresTest`: 1 passed, 0 failures, 0 skipped
4. **全量相关测试集**：
   - `Knowledge*Test`, `CrossDocument*Test`, `Retrieval*Test`: 92 passed, 0 failures, 1 skipped

---

## 七、交付状态核定

综上全部真实证据与自动化测试：
- Q09 生产代码修复精准，彻底消除了跨文档同文本来源身份丢失的问题；
- 冻结质量回归无新增退化；
- 真实 Gemini 3.5 Flash Lite 端到端回答准确确认两份合同一致、都要求项目负责人书面批准、并分别精准引用了合同甲 [S1] 和合同乙 [S2]；
- 数据库持久化双引用一致性校验 100% 通过；
- 消耗请求严格控制在 3 次（预算 6 次以内）。

确认最终交付状态为：
**`PRODUCTION_FIX_AND_REAL_GEMINI_ACCEPTED`**
