# P9-RAG-GENERALIZATION-01 — 独立 RAG 泛化评测基准设计

> **状态：IMPLEMENTED / `P9_GENERALIZATION_BASELINE_ACCEPTED`（基准建设与真实测量；生成层 REAL_GENERATION_NOT_EXECUTED）**
> **统计口径修正：** 见 [p9 errata](../acceptance-evidence/2026-10-26/p9-rag-generalization-01-errata/README.md)（长文档实际 721–3,353 cp；唯一 23 篇/161 chunks，175 为累计入库事件；泛化结论边界收敛）。
> **冻结基线：** `dev@3891171ecfc81a3929993f9f77989fed61ea5c85`（2026-10-26）
> **实施入口：** `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/P9RagGeneralizationPostgresTest.java`
> **语料与标注：** `tests/fixtures/documents/p9-rag-generalization-01/`
> **基线证据：** `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`
> **范围：** RAG 泛化能力测量（QA 与 Agent 双路径）。**不修改任何生产检索、分块、来源选择、Prompt、Agent Runtime 或引用算法。**

## 1. 为什么需要独立基准

P4～P8 的冻结题集（13 篇语料 / 16 题）在多轮算法迭代中同时承担了"发现问题的探针"与"验收的标尺"两个角色。P8 结束时 QA 保留 26/27、Agent 保留 22/27 关键事实，但该数字建立在旧语料、旧问题措辞之上，无法回答：

1. 文档变长（721→3,353 code points，6,000+ 量级尚未覆盖）、版本增多、证据分散、措辞变化时，当前 RAG 能否稳定找到正确事实？（长度口径修正见 errata）
2. 旧集上的 26/27 中有多少是语料特性带来的红利，有多少是策略的真实能力？

因此 P9 建立一套**独立于 P4～P8 冻结集**的泛化检验集：全新业务域、全新措辞、覆盖八个失效维度，并划分 Development（调试评测框架本身）与 Holdout（方法学保留集）。

## 2. 基准构成

### 2.1 语料（23 篇，UTF-8 无 BOM，全部合成公开资料）

| 族 | 业务域 | 集合 | 覆盖维度 |
| --- | --- | --- | --- |
| A | 设备维护（巡检简则/管理细则/中央机组手册/全厂设施总册） | dev | G01 长文档信息稀释（721/1,436/2,633/3,353 code points，事实置于开头/中段/章节交界/尾部；6,000+ 量级未覆盖，见 errata） |
| B | 服务交接（申请条件/审批流程/时限/例外） | dev | G02 跨文档组合（2～4 篇）、G03 同文档互补事实 |
| C | 资产盘点（华东/华南/总部三份高相似规范 + 争议补充） | dev | G06 相似正文不同来源身份、同文档重复段落 |
| D | 审批与合同（差旅费 V1/V2/补充通知 + 培训合同办法） | holdout | G04 多版本冲突（现行/废止/变化追溯）、G05 条件与例外（必须/建议/除非/或/超时人工确认） |
| E | 技术变更（规程/评审细则/紧急变更办法） | holdout | G05 否定条件、G03 互补、G08 口语改写、G01 中长文档尾部事实 |
| F | 审计核对（工作指引/证据规范/待定备忘/外部未审核摘录） | holdout | G07 无答案/相关但无直接答案/明确未定/表面相似干扰/提示注入（按 Q13 协议临时索引） |

规模：39 题（dev 21 / holdout 18），54 条必答事实，61 个冻结 marker（含 7 个干扰条款标注），2 道拒答题，1 道注入题。唯一语料 23 篇 / 161 chunks（常驻 22 篇 / 154 chunks；证据中的 25/175 为累计索引事件，口径见 errata）。

### 2.2 防过拟合与泄漏红线

- 新语料业务域与 P4 冻结集（发布/合同/导出/预算等）完全不同，事实数字、文件名、措辞全部重新设计；改写题（G08）的问法不含答案关键字。
- **Development / Holdout 按文档族划分**（A/B/C ↔ D/E/F），不存在"同文档改数字"式泄漏。
- `holdout-integrity.json` 在首次测量前冻结全部 holdout 文档、问题文件的 SHA-256。Holdout 是**方法学保留集**而非密码学盲测：冻结后不得依据其结果调整生产规则或评测参数；一旦被任何候选方案消费即标记已消费并另建新集。
- **标签隔离**：facts/distractFacts 的 marker 只用于离线评分。测量代码中 marker 仅出现在 SQL LIKE 定位（评分侧）与逐事实归因，绝不进入检索 query、来源选择或任何生产路径；生成"正确回答"的路径不存在（本轮无 Chat 模型）。
- 题目不通过 ID/文件名特判检索行为；测试类对全部题目使用同一生产参数。

### 2.3 冻结清单

`corpus-manifest.json`（23 篇 sha256 + code point 数）、`question-manifest.json`（dev）、
`question-manifest-holdout.json`、`holdout-integrity.json`。逐字校验由
`docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/generate-manifests.cjs` 完成
（每个 marker 必须是其所属文档的逐字子串，dev 题不得引用 holdout 语料事实）。

## 3. 测量协议（沿用 P6，生产参数零改动）

- 真实本地 Ollama `qwen3-embedding:0.6b`（1024 维）+ Testcontainers `pgvector/pgvector:pg17`；
  生产 `DocumentChunker` 1200/150 分块、真实向量索引、真实 SQL 检索。
- **QA 路径**：`DocumentSearchService.search(topK=8)` + `KnowledgeContextBuilder.buildBalancedWithDiagnostics(5)`，
  8000 code points 预算；另记 legacy `buildWithDiagnostics(5)` 对照。
- **Agent 路径**：真实执行生产 `KnowledgeSearchAgentTool`（内部候选池 20、默认 limit 8、
  quote 600 code point 截断、真实 `ProjectAccessGuard` 与用户范围解析），另记 limit=12 对照。
- 全量排序（top 1000）仅作诊断归因，绝不回填生产候选。
- 每题记录：候选排名（Top8/Top20/全量）、QA/Agent 来源保留、丢弃原因、来源身份与数据库核对、
  范围越界计数、false evidence（拒答题干扰文档是否进入上下文）、冲突条款暴露、延迟与 code points 成本。
- **事实级归因**：一个事实由"内容包含 marker 的全部 chunk"共同承载（容忍同文重复段落）；
  分层结论 INDEX_MISS / RETRIEVAL_MISS / SELECTION_MISS:原因 / BODY_TRUNCATION / RETAINED。
- Agent 工具 JSON 与数据库逐条核对（quote 必须是 chunk 内容前缀、documentId/chunkId/filename 一致、
  ≤600 code points）；composer 传输一致性已在 P8 同 SHA 验证，本轮不重复执行完整 Agent Runtime。

## 4. 指标

| 指标 | 定义 |
| --- | --- |
| Recall@8 / Recall@20 | 必答事实（任一载体 chunk）进入 QA Top8 / Agent Top20 候选 |
| QA Evidence Retention | 事实出现在 QA 最终上下文且 marker 完整可见 |
| Agent Evidence Retention | 事实出现在真实工具输出 quote 且 marker 完整可见 |
| Complete Evidence Coverage | 单题全部必答事实均 RETAINED 的题目占比 |
| Truncation Loss | 事实载体入选但 600 code point 截断丢失 marker |
| False Evidence | 拒答题中干扰文档进入最终上下文/工具摘录 |
| Version Conflict Exposure | 已废止/他方条款同时被保留为最终可见证据 |
| Source Identity Accuracy / Scope Violation | 工具来源身份与数据库一致 / 越界来源数 |
| Retrieval Latency / Evidence Assembly Cost | 分路径延迟；来源数、quote 总 code points、prompt code points |

汇总维度：dev/holdout、八维失效类型、文档长度档、跨文档数档、事实数档；
每档输出 micro 保留率与完整覆盖。code points 不是 token 数，报告中不混用。

## 5. 与历史冻结集的关系

| 测试集 | 用途 | 基线 |
| --- | --- | --- |
| P4～P8 旧冻结集 | 回归保护（每轮重跑 `P6RagQualityBaselinePostgresTest`） | QA 26/27、Agent 22/27（背景值，非预期值） |
| P9 Development | 验证评测框架与数据质量 | 本轮建立 |
| P9 Holdout | 泛化检验 | 本轮建立（冻结后仅测量，不调参） |

两个集合同 SHA、同 embedding、同运行条件下分别测量，不跨集拼接指标。

## 6. 运行方式与 CI 隔离

```bash
# 显式 opt-in（本地，需 Docker + 本地 Ollama）
AI_REAL_OLLAMA_RETRIEVAL=true bash ./mvnw -B -ntp test \
  -Dtest=P9RagGeneralizationPostgresTest -Drag.baseline.sha=<HEAD>
# 默认 CI：该类显式 @EnabledIfEnvironmentVariable 门控，验证为 Skipped
```

测试类**显式声明**环境门控（不依赖 JUnit 条件继承，P8 CI 门控教训）；默认 `mvnw verify`
中该类跳过，真实 PostgreSQL 的非模型测试不受影响。输出仅写 `target/p9-rag-generalization-01/`。

## 7. 已知边界

- 不调用任何外部 Chat/付费模型；真实 Chat 生成质量结论恒为 `REAL_GENERATION_NOT_EXECUTED`。
- 注入样本（F4）仅在 H-G07-NONE / H-G07-SIMILAR / H-G07-INJECT 期间临时索引（Q13 协议），
  因此这两道非注入题的候选竞争与生产长期状态略有差异。
- 独立基准的一次测量不能证明泛化上限；持续维护（新增语料/题目时重算 manifest 并登记 README）是基准价值的前提。
