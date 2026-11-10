# AI Collab RAG V2｜真实质量评测与 V1/V2 对照终审报告

> 任务：RAG_V2_QUAL_EVAL-01（独立质量终审，非功能开发）
> 评测执行日期：2026-11-07
> 评测代码：HEAD `322ba9f6`（dev）；V1 基线：`82078803`（独立 worktree）
> 最终裁决：**RAG_V2_QUALITY_REGRESSION**（详见 §7）

---

## 1. 评测环境

| 项 | 值 |
| --- | --- |
| Git HEAD（V2） | `322ba9f6`（dev，2026-11-06 20:25 封版报告提交） |
| V1 基线 | `82078803`（V2 重构的直接父提交），独立 worktree `ai-collab-backend/target/rag-v1-baseline` |
| 数据库 | Testcontainers `pgvector/pgvector:pg17` 一次性容器，Flyway V1–V80 全量迁移（两版各自独立实例） |
| Embedding 模型 | 本地 Ollama `qwen3-embedding:0.6b`，1024 维，fingerprint 冻结（两版一致） |
| 生成模型 | 魔搭 ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`，temperature=0，maxOutputTokens=1024（用户指定授权配置；路由快照从生产库 SELECT 复制，密钥零落日志） |
| 评测语料 | P9 冻结泛化语料 23 篇（manifest sha256 逐字节校验）+ P10 冻结题集 14 题；两版语料 diff 为空 |
| 评测样例总数 | P9 39 题（dev 21 + holdout 18）/ 54 条必答事实；P10 14 题；多轮场景 2 组 × 3 轮；元数据过滤 2 例；消融 5 变体 × 39 题 |
| 真实执行命令 | `AI_REAL_OLLAMA_RETRIEVAL=true ./mvnw test -Dtest=P9RagGeneralizationPostgresTest`；`AI_RAG_V2_QUAL_EVAL=true ./mvnw test -Dtest=RagV2QualityEvaluationPostgresTest`；`AI_REAL_OLLAMA_RETRIEVAL=true ./mvnw test -Dtest=AgentRetrievalQualityPostgresTest`；`./mvnw test -Dtest=RagV2ChineseQueryRerankRegressionTest` |
| 真实模型调用 | 生成 14 题：V2 全部空上下文未消耗调用；V1 对照 13 次成功执行（1 题因评测夹具 scope 顺序缺陷缺失，见 §5-L4） |

## 2. 历史基线核查结论

历史数字 **52/54 (96.3%)** 与 **51/54 (94.4%)** 出自 `docs/acceptance-evidence/2026-10-26/p9-rag-generalization-01/`，含义为 P9 泛化基准上 QA 路径与 Agent 工具路径的**必要事实保留率**（检索/上下文层指标），**不是回答准确率**；历史生成层一律标注 `REAL_GENERATION_NOT_EXECUTED`。核查属实。

本次在当前环境复测验证：

| 复测 | QA 保留率 | Agent 保留率 | 与 2026-10-26 逐题归因对比 |
| --- | --- | --- | --- |
| V2 主树（P9 冻结协议） | 52/54 (96.3%) | 51/54 (94.4%) | **54/54 事实归因零差异** |
| V1 worktree（P9 冻结协议） | 52/54 (96.3%) | 51/54 (94.4%) | 语义等价（仅 UUID/键序差异） |

**关键方法学发现**：P9 冻结测试的 QA 路径（`search(8)+buildBalanced(5)`）与 Agent 工具（4 参构造）都绕过了 V2 的 `RetrievalPipeline` 注入——P9 数字在两版代码上等价，**它测量的是 dense 直连 + 上下文装配协议，不测量 V2 生产管线**。因此历史数字不能作为"V2 已验证"的依据；真正的 V2 生产路径评测由本次新增的 `RagV2QualityEvaluationPostgresTest` 完成。

## 3. 核心指标对照表

| 指标 | RAG V1（实测） | RAG V2（实测） | 变化 |
| --- | --- | --- | --- |
| QA 必要事实保留率（P9 54 事实，V1 协议 vs V2 生产管线） | **52/54 (96.3%)** | **6/54 (11.1%)** | **-85.2pp（回归）** |
| Agent 工具必要事实保留率（54 事实） | **51/54 (94.4%)** | **0/54 (0%)** | **-94.4pp（回归）** |
| QA 空上下文题比例（39 题） | 2/39 (5.1%) | 33/39 (84.6%) | **+79.5pp（回归）** |
| P10 生成题有效回答比例（14 题） | 13/14 有上下文并生成 | **0/14（全部空上下文统一拒答）** | **回归** |
| 回答事实纪律（高严重度违规，13 次真实生成） | 3 项（AQ11/AQ12 无答案题编造；10 题事实题零违规） | 无法测量（0 次生成，NOT_VERIFIED） | — |
| 引用合法性 | 全部通过 `KnowledgeCitationValidator` 校验 | 无法测量（NOT_VERIFIED） | — |
| 正确拒答率 | 1/1（无答案题正确拒答）+ 2 题错误部分编造 | 14/14 拒答，但拒答源于缺陷而非证据判断 | 口径 NOT_COMPARABLE |
| dense 直连检索质量硬门限（Recall@8/@20，D-RAG-01C） | 0.9545（21/22） | **0.9545（21/22，同样达标）** | 0（未退化） |
| 检索 P50 延迟（39 题，本地环境参考值） | 82ms | 196ms | +114ms（pipeline 各阶段开销） |
| 检索 P95 延迟 | 377ms | 607ms | +230ms（样本 39，非生产指标） |
| Chat 延迟 P50（真实生成） | 3538ms | 未执行 | — |
| Token/成本 | 13 次生成（usage 未逐题落盘，估算 <3 万 tokens，Flash 档成本可忽略） | 0 次生成调用 | — |

消融实验（§4）显示所有 V2 管线变体（含 denseOnly）事实保留率均为 0/54——回归由管线输出分数语义与装配阈值的不匹配引起，与组件开关无关。

## 4. 消融实验结果

| 变体 | 配置 | 事实保留率 | 结论 |
| --- | --- | --- | --- |
| denseOnly | lexical=0, window/parent off, PASS_THROUGH | **0/54** | RRF 融合输出分数（1/(60+rank)≈0.016）替换 similarity 后低于阈值 0.15 → 全丢 |
| hybridNoExtras | 混合检索, 无 window/parent, PASS_THROUGH | **0/54** | 同上 |
| hybridWindow | 混合 + 句子窗口 | **0/54** | 同上 |
| hybridParent | 混合 + 父子回读 | **0/54** | 同上 |
| fullDefault | 完整默认管线（启发式重排） | **0/54** | 启发式重排分数更低（≈0.006），同样全丢 |

**每个组件的真实贡献被装配层缺陷完全掩盖，无法测量**（NOT_VERIFIED）。消融的目的本是回答"每个组件贡献了什么"，本轮只能回答"管线输出侧存在全局性阻断"。组件级收益（Lexical 对精确术语、Window 对长文档、Parent-Child 对跨章节）留待缺陷修复后的复测。

可确认的次要发现：
- Lexical 召回偏弱：39 题 lexical 候选合计 114（平均 2.9/题），pg_trgm 对中文词项召回有限。
- 元数据硬过滤在候选层有效：MF2 过滤后候选 8→6，干扰文档（V1 旧标准）被正确剔除（RETRIEVAL_MISS），机制本身闭环，但收益同样被装配层阻断掩盖。
- 多轮改写有行为但未提升：`KnowledgeConversationContext` 对追问做了历史拼接改写（如"总部本部…频率？\n那华南中心呢？"），改写质量未被装配层缺陷掩盖前无法评定。

## 5. 质量问题列表

### HIGH-1｜V2 生产检索管线对中文查询系统性空上下文（真实缺陷，本轮最高优先级发现）

- **现象**：`RetrievalPipeline` 输出的 `DocumentSearchHit.similarity` 被替换为有效分数（`rerankScore ?? rrfScore`，量级 0.006–0.016），而 `KnowledgeContextBuilder.MIN_SIMILARITY=0.15` 按余弦相似度语义过滤。39 题共 **374 项候选被 BELOW_SIMILARITY_THRESHOLD 丢弃**，V2 QA 33/39 题、生成 14/14 题空上下文 → 生产知识问答/协作 Agent 检索将统一回答"资料不足"。
- **根因**：接口契约不匹配——`RetrievalCandidate.toDocumentSearchHit()` 用 RRF/重排分数冒充余弦相似度；`SemanticHeuristicReranker.extractTerms` 以标点/空白切词，中文整句成为单一超长 term，contentScore/headingScore≈0，使重排分数进一步坍缩至 rrf×0.35。**连带标点查询同样失败**（实测 maxEffectiveScore=0.0057 < 0.15）。
- **最小复现**：`RagV2ChineseQueryRerankRegressionTest`（离线确定性，已随本报告提交 dev）：同一批候选，V1 协议装配保留 3 源且含目标事实，V2 生产路径装配 0 源。
- **影响面**：`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService`、`KnowledgeSearchAgentTool`（6 参注入管线构造）三条生产链路全部受影响。**为什么封版验证未发现**：2026-11-06 封版专项 A 验证的是调用链各环节"生效性"（索引→召回→窗口→装配），2210 项回归不含真实中文查询的端到端装配非空断言；P9 冻结测试（52/54）因绕过管线注入无法暴露。

### MEDIUM-1｜元数据过滤收益与多轮收益不可评定（NOT_VERIFIED）

硬过滤与多轮改写机制在候选层已生效（见 §4），但端到端收益被 HIGH-1 阻断，本轮无法给出质量数字。修复 HIGH-1 后需复测。

### LOW-1｜门控测试断言未随 V2 更新（真实缺陷，测试资产级）

`AgentRetrievalQualityPostgresTest.pairedProductionChunkingOnHistoricalCorpus` 在 V2 主树失败：断言构造的 `metadata` 含 `chunkType=STANDARD`，而 V2 chunker 的 in-memory 块 metadata 无该键（落库序列化时才有）。该测试属 `AI_REAL_OLLAMA_RETRIEVAL` 门控，封版 `mvnw verify` 不触发，故 2210 项全绿未覆盖。

### LOW-2｜Lexical 中文召回偏弱（真实缺陷，低影响）

pg_trgm 词项检索 39 题平均 2.9 候选/题，对中文分词场景贡献有限；具体增益待 HIGH-1 修复后用消融复测量化。

### LOW-3｜评测夹具缺陷（非产品缺陷）

AQ14-INJECTION 的 V1 对照缺失：评测代码中 `resolveScope` 先于 F4 临时索引执行，取到上一题已置 FAILED 的旧文档 UUID。13/14 数据不受影响。评测代码随本报告提交并留有 `scopeDiagnosis` 诊断字段。

### 模型能力限制（非产品缺陷）

V1 真实生成的 3 项高严重度违规（AQ11 无答案题编造数字、AQ12 禁断表述）属模型在无证据场景下的幻觉倾向，V1 装配已提供正确证据与引用契约，生成侧问题与 RAG 架构无关。V2 侧同类问题无法测量（NOT_VERIFIED）。

## 6. 评测资产归档

| 资产 | 位置 |
| --- | --- |
| 核心评测原始结果（39 题 × 8 路径 + 消融 + 多轮 + 过滤 + 真实生成） | `docs/acceptance-evidence/2026-11-07/rag-v2-quality-eval-01/rag-v2-quality-eval-results.json` |
| P9 协议复测（V2 主树 / V1 worktree） | 同目录 `p9-protocol-rerun-v2-tree.json` / `p9-protocol-rerun-v1-worktree.json` |
| Agent 硬门限结果（V2 主树） | 同目录 `agent-gate-v2-tree.json` |
| 证据说明与复现命令 | 同目录 `README.md` |
| 评测测试代码 | `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/RagV2QualityEvaluationPostgresTest.java`（门控 `AI_RAG_V2_QUAL_EVAL=true`） |
| 缺陷最小复现 | `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/RagV2ChineseQueryRerankRegressionTest.java`（CI 常跑） |
| 冻结语料与标准答案 | `tests/fixtures/documents/p9-rag-generalization-01/`（未改动，manifest sha256 校验通过） |
| V1 基线 worktree | `ai-collab-backend/target/rag-v1-baseline`（git worktree，82078803，评测后可清理） |

未归档任何密钥或未授权私有原文；语料均为仓库内公开合成测试语料。

## 7. 最终评测裁决

# `RAG_V2_QUALITY_REGRESSION`

依据（§9 底线条款第 3/4 条）：

1. **明确的重大质量退化**：真实中文查询下，V2 生产检索管线将事实保留率从 96.3%/94.4% 打至 11.1%/0%，P10 生成场景 14/14 空上下文拒答；回归在 39 题全量与 5 个消融变体上稳定复现，并已用离线确定性最小复现测试固化。
2. **安全底线未破**：权限/范围/身份机械核验零违规，dense 直连路径硬门限保持历史水平，冻结语料与历史数字可复现——退化被精确隔离在 `RetrievalPipeline → toDocumentSearchHit() → KnowledgeContextBuilder` 的分数语义断层，修复面明确。
3. **工程封版结论不受本裁决影响**：2026-11-06 的工程研发封版（2210 项回归、0 失败）是**工程测试事实**，本报告不推翻；两项结论独立——**工程封版成功 ≠ 真实质量通过**。
4. 本轮按要求未做任何 RAG 功能重构；HIGH-1 的最小修复方向（管线输出层保留原始余弦相似度字段供装配层阈值判定，或装配层按分数类型区分阈值语义）已记录在问题列表，留待后续任务处置。

### 核心指标对照表（裁决摘要）

| 指标 | RAG V1 | RAG V2 | 变化 |
|---|---|---|---|
| Recall@5 / Recall@10（dense 直连） | 基线内 | 基线内（21/22） | 无退化 |
| 事实保留率（QA / Agent，生产管线口径） | 96.3% / 94.4% | 11.1% / 0% | **-85.2pp / -94.4pp** |
| 来源覆盖率 | 历史水平 | NOT_VERIFIED | — |
| 回答事实正确率 | 10/10 事实题零高严重度（13 次真实生成） | NOT_VERIFIED（0 次生成） | — |
| 引用支持率 | 校验全部通过 | NOT_VERIFIED | — |
| 正确拒答率 | 1/1 | 14/14（拒答正确但成因是缺陷，口径 NOT_COMPARABLE） | — |
| P95 检索延迟 | 377ms | 607ms | +230ms（样本 39，非生产指标） |
| Token / 成本 | 13 次调用，Flash 档可忽略 | 0 次 | — |
