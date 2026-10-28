# P6-RAG-QUALITY-01 验收证据：RAG 检索质量、证据与引用契约

> **裁决：`P6_RAG_QUALITY_01_ACCEPTED`（范围限定：检索/证据/引用契约层；端到端生成质量未执行，见 §6）**
> 基线 SHA：`d9554c301a8a82c986630ba085e97ea6e1e7fcdf` · 实施分支：`dev`
> 设计：[docs/design/rag-quality-and-evidence.md](../../../design/rag-quality-and-evidence.md)
> 研究详情：[docs/archive/research/p6-rag-quality-01-research-20261025.md](../../../archive/research/p6-rag-quality-01-research-20261025.md)

## 1. 证据文件

| 文件 | 内容 | 产生方式 |
| --- | --- | --- |
| `d0-baseline-pre-s2.json` | 改动前当前 SHA 的 D0/D1/D2 完整基线（QA=A、Agent、R1a/R1b、S1 对照），16 题 × 逐事实分层归因 | `P6RagQualityBaselinePostgresTest`（AI_REAL_OLLAMA_RETRIEVAL=true，本地 Ollama + Testcontainers pgvector） |
| `d0-d2-final.json` | 生产 S2（每文档名额均衡）切换后的同口径终态测量（qaStage=生产路径，qaLegacyStage=A 对照） | 同上（改动后重跑） |
| `selection-experiments.json` | 冻结回放（network-recovery-03 真实历史候选，23 项事实）A/S1/S2/S3 逐事实 gained/lost | `P6QaSourceSelectionExperimentTest`（离线确定性） |

原始输出同源于 `ai-collab-backend/target/retrieval-baseline/p6-rag-quality-01/`（target 不入库）；
此处为脱敏可复核副本（合成公开语料、随机 UUID、无凭据）。**2026-10-22 历史冻结 JSON 未被覆盖或改名。**

## 2. D0 基线复现（对照 P4 历史）

| 项 | 当前 SHA 实测 | 历史（2026-10-22） | 判定 |
| --- | --- | --- | --- |
| Q02 续发前提 | 全量排名 23、相似度 0.3643；QA Top8/Agent Top20 均 RETRIEVAL_MISS | P4-Q02 A 方案全量排名 23、0.364258 | 忠实复现，候选层缺失 |
| Q03 上线门禁 | QA 候选第 6 名 `SELECTION_MISS:SOURCE_CAP`（候选 2–5 名全部来自《发布回滚预案》） | network-recovery-03：候选第 6 名被默认 5 来源挤掉 | 忠实复现，选择层缺失 |
| Q09 双合同身份 | QA 与 Agent 双路径两份独立来源均保留 | 旧代码 CROSS_DOCUMENT_DUPLICATE 丢失 | **当前 SHA 已修复，仅验证未重复修** |
| 语料完整性 | 14 篇逐字节 sha256 == corpus-manifest.json | 同 manifest | 冻结无漂移（fixtures 目录 2026-10-24 新增的第 15 篇 E2E 语料已按 manifest 排除） |
| E1–E11 反例 | `CrossDocumentSourceSelectorStrategyExperimentTest` 等既有套件全绿 | P4-Q03 全绿 | 无倒退 |

## 3. D1 候选召回（R1a/R1b）与 D2 选择实验结论

- **R1a**（ASCII 编号/《》标题 SQL LIKE 补充，≤4 条、每题 1 条 SQL、0 额外向量）：冻结题集仅 Q06 有 `REQ-7741` 且本就排名 1，零增量。
- **R1b**（中文二元组命中排序补充）：把 Q02 目标补进候选池（真实相似度 0.3643/全量排名 23），
  但 QA/Agent 最终上下文均仍 `SELECTION_MISS:SOURCE_CAP`——**候选池召回改善无法转化为证据保留**，分支终止，不入生产。
- **S1**（跨文档代表优先，Agent 同款选择器用于 QA 5 名额）：冻结回放 20/23、真实 24/27，
  **丢失 Q05 v1.9 关键事实**（同文档第二事实被代表名额挤出），违反零关键事实回归门槛，**否决**。
- **S2（准入）**：候选顺序 + 每文档名额 cap=2（两遍填充、全量重排保回填；仅 documentId 身份信号，
  非已否决的 D/E 字面匹配家族；候选集/名额/预算/TopK/scope 全不变）：
  - 冻结回放 **22/23、零回归、救回 Q03/阻断级缺陷**；
  - 真实当前 SHA **26/27（legacy A 为 25/27）、零回归**，Q03 三阶段证据（上线门禁+两小时灰度+连续两次阈值）全部进入 QA 上下文；
  - 确定性反例矩阵 9 项（跨文档身份/同文档两事实/cap 边界权衡/名额洪泛/弱干扰/确定性/预算/非竞争恒等/阈值回填）全部按预期；
  - 已知权衡：同文档第 3 条必需互补事实让位（CE3 记录；冻结题集同文档必需事实 ≤2 条，不触发）。
- **Agent 路径维持现状**：limit=8 下 Q03 上线门禁（代表拥挤）、Q03 回滚触发条件、Q04 导出审计、Q05 v1.9 共 4 项被挤；limit=12 可救回 3 项，但提高默认 limit 的 token 成本与端到端收益需真实 Chat 授权验证，本轮仅记录。

## 4. 生产变更清单（本轮准入）

| 变更 | 文件 | 安全性论证 |
| --- | --- | --- |
| QA 来源选择：每文档名额均衡 `buildBalanced*`（cap=2） | `knowledge/domain/service/KnowledgeContextBuilder.java`、`KnowledgeQuestionApplicationService`、`KnowledgeStreamQuestionService` | 纯检索后重排：0 额外查询/向量/名额/预算变化；非竞争场景与旧行为逐字节恒等；冻结回放+真实数据+反例矩阵三重零回归；Q09 身份语义与按文档去重不变 |
| 引用校验：受限分组引用 `[S1, S2]` 逐成员解析 | `knowledge/domain/service/KnowledgeCitationValidator.java` | 机械契约修复：合法分组不再丢失持久化引用；部分非法仅移除非法成员；畸形括号不误伤；单项行为完全向后兼容（17 例确定性测试） |

未改动：`DocumentChunker` 1200/150、Embedding 模型/fingerprint/generation、TopK 上限、来源总数上限（QA 5 / Agent 12）、
`CrossDocumentSourceSelector`（Agent 选择器）、历史 Flyway、历史索引与历史冻结证据。

## 5. 成本与延迟口径（真实测量）

| 维度 | 数值 |
| --- | --- |
| 索引（14 篇冻结语料） | 49 分块、13,444 code points 输入、14 次向量请求、总计 4,316ms（本地 Ollama） |
| 单题查询向量调用 | QA 1 次、Agent 1 次（与生产两条路径一致；诊断全量排序 1 次仅测试用途不计成本） |
| S2 额外成本 | 0 次查询、0 次向量调用、0 额外名额；纯内存重排 |
| QA 检索延迟（pgvector，真实） | 16 题 min 37ms / max 125ms（单次测量口径，非 P95 承诺） |
| 上下文预算 | QA/Agent 全部 ≤8000 code points（逐题断言） |
| 范围隔离 | 指定文档题（Q10/Q11/Q13/Q15/Q16、Q12）候选与来源越界数=0（逐题断言） |

## 6. 验证范围声明（防伪结论）

- **已验证（真实）**：真实本地 Ollama `qwen3-embedding:0.6b`（1024 维，fingerprint 与 P4 一致）+ 真实 Testcontainers pgvector 走生产 `DocumentSearchService`→Repository→Mapper SQL；冻结历史候选离线回放；引用契约确定性测试；E1–E11。
- **未执行**：外部 Chat 模型端到端回答质量（Gemini 24/24 预算已用完，本轮无用户新授权，零外部调用、零凭据读取）；
  `REAL_ANSWER_QUALITY_ACCEPTED` **不成立**亦未声称。CI 套件中 AI_REAL_OLLAMA_RETRIEVAL / AI_GEMINI_LIVE_TESTS 门控用例按预期跳过，不以跳过冒充通过。
- **结论拆分**：`RETRIEVAL_QUALITY_RESULT`＝真实通过（含一项准入改善与一项如实遗留）；
  `CITATION_CONTRACT_RESULT`＝确定性通过；`END_TO_END_GENERATION_RESULT`＝NOT_EXECUTED（无授权）。

## 7. 复现方式

```bash
# 真实基线与实验（需 Docker + 本地 Ollama qwen3-embedding:0.6b）
cd ai-collab-backend && AI_REAL_OLLAMA_RETRIEVAL=true bash ./mvnw -B -ntp -Dtest=P6RagQualityBaselinePostgresTest test
# 冻结回放与反例矩阵（离线确定性，CI 常跑）
bash ./mvnw -B -ntp -Dtest=P6QaSourceSelectionExperimentTest,FrozenKnowledgeQualityRegressionTest,KnowledgeCitationValidatorTest test
```
