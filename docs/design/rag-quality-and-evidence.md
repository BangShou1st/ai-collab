# P6-RAG-QUALITY-01 — 真实检索、证据保留、引用支持与成本优化设计

> **状态：IMPLEMENTED / `P6_RAG_QUALITY_01_ACCEPTED`（限检索/证据/引用契约层；生成层 NOT_EXECUTED，见验收证据）**
> **设计基线：** `dev@d9554c301a8a82c986630ba085e97ea6e1e7fcdf`（2026-10-25）
> **实施结论：** QA 每文档名额均衡选择（S2）与分组引用解析准入生产；Q02 召回与 Agent limit=8 挤压如实遗留。
> **验收证据：** `docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/README.md`
> **范围：** Project Knowledge QA + Agent `search_project_knowledge` 的检索质量、上下文证据保留、引用有效性及成本测量。
> **原则：** 不先改生产参数，不盲目引入重排模型；先复现问题，按证据批准改动。

## 0. 结论与推荐路线

**不批准立刻将 1200/150 改为 800/120，也不批准直接上线已有 D/E SOURCE_CAP 字面启发式。** 历史 P4-RAG-QUALITY-02/03 的真实 Ollama 对照已给出反例：800/120 能将关键续发条款从全量第 23 名带到候选 Top20 的第 17 名，但最终仍可能因 SOURCE_CAP 而没有出现在模型上下文；D/E 有关键证据替换退化。P4 对生产策略作出 `NO_CHANGE_EVIDENCE_INSUFFICIENT` 裁决，P6-D 不得将其写成“历史已推荐上线”。

采用 **D0 当前版本基线 → D1 候选召回实验 → D2 上下文选择实验 → D3 引用与回答质量 → D4 成本/回归/上线判断**。实验先于代码改造；每阶段允许 `NO_CHANGE`，**不要求为了完成专项强行改动生产算法**。

**知识库问答（QA）与 Agent 两条不同路径分别测量：**
- QA：`KnowledgeQuestionApplicationService` 使用 `DocumentSearchService.search(..., TOP_K=8)`、`KnowledgeContextBuilder.build()` 默认 **最多 5 个来源**、上下文 **8000 code points**。随后模型回答由 `KnowledgeCitationValidator` 解析单项 `[S数字]`，将引用写入持久化记录。
- Agent：`KnowledgeSearchAgentTool` 以 **Top20** 向量候选，使用 `buildDiverse(hits, limit)`，工具默认 limit=8、可设 1–12；工具输出每条 quote 再截断至 **600 字符**，context builder 总上限同为 8000 code points。**Agent 的两阶段跨文档选择与 QA 默认的按候选顺序选择不是同一算法**。

## 1. 已有证据与必要复核

### 1.1 不可忽略的 P4 历史

| 证据 | 已有结果 | 本轮决策影响 |
|---|---|---|
| `docs/acceptance-evidence/2026-10-22/p4-rag-quality-02/README.md` | 真实本地 Ollama `qwen3-embedding:0.6b`（1024 维）、隔离 pgvector；完整语料重建 A=1200/150、B=800/120；尾部条件全量排名 A23 → B17，但最终上下文均未保留 | 分块改变与最终回答质量不是同一个指标，不得只凭 Top20 召回批准上线 |
| `docs/acceptance-evidence/2026-10-22/p4-rag-quality-03/README.md` | 实测 D/E 来源选择策略存在关键证据损失，均 **未获批准**；联合 B+E 的收益主要见于 limit=12，不适用于默认 8 的全部场景 | 不重新包装已否决启发式；必须有逐题 gained/lost 与反例回归 |
| `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/network-recovery-03/README.md` | Gemini 真实回答已取得 14 道，3 道不完整；Q02=RETRIEVAL_MISS，Q03/Q09=CONTEXT_MISS（伴随引用支持问题）；另有 6 场景未执行，整体 `PARTIAL_REAL_ACCEPTANCE` | 冻结问题和人工标准是 D0 的首选高价值样本，不冒充已全面验收 |
| `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/rubric.md` | 10 个人工维度，明确 Reference Validity 与 Citation Support 不等价，关键失败单独判 | 沿用审查口径，禁止让 Gemini 自评分并当作真值 |
| `tests/fixtures/documents/p4-rag-answer-quality-01/` | 13 篇历史重建公开合成语料 + 1 篇注入样本，UTF-8、冻结 hash | 只读复用，禁止为了提高分数修改原语料或原问题标签 |

**历史 Q02：** `继续发布需要满足什么前提条件？` 的正确条件未进入 QA Top8，属于候选缺失，不能用只改上下文选择器解决。
**历史 Q03：** 上线门禁在 QA 候选第 6 名、默认 5 来源挤掉；可以先在生产 `build()` 的同预算条件下试验来源选择，不得未经测试直接扩大来源数。
**历史 Q09：** Gemini 运行时曾因跨文档正文相同而丢失合同甲的独立来源身份；**但当前 `KnowledgeContextBuilder.buildWithDiagnostics()` 源码按 `documentId` 分别做 hash 去重，当前 Agent `buildDiverse()` 也按文档分别去重**。因此 Q09 必须在当前 SHA 上重新测试，不能把旧结果未经复验写成仍未修复的生产缺陷。

### 1.2 版本化且不可混淆的两个范围

- **ALL_PROJECT_PRIMARY：** 用户没有明确指定文档，必须全项目检索。标注“期望来源”不能当作用户传入的 scope。
- **DOCUMENT_SCOPED_PRIMARY：** 用户在原问题或请求参数中明确限定文档，必须按同一 scope 检索与引用。
- 可另测 `SCOPED_ALL_PROJECT_REFERENCE`，但它是反事实实验，**不得混入上述主结果分母**。

真实 Embedding 评测 `AgentRetrievalQualityPostgresTest` 由 `AI_REAL_OLLAMA_RETRIEVAL=true` 门控；普通 CI 跳过是预期事实，不能当作真实 Embedding 合格证明。`GeminiKnowledgeAnswerQualityPostgresTest` 为外部调用 opt-in，历史有 24 次授权上限已用完；**不能私自再次调用 Gemini、绕过限额或读取用户私有凭据**。

## 2. D0 — 先冻结当前 SHA 的真实质量基线

首先只运行本地离线/真实 PostgreSQL、无需外部模型的测试；Ollama 若可用，可在显式允许下运行已存在的真实 Embedding 套件。所有实验输出保存到 `ai-collab-backend/target/` 或新的受控验收目录，**严禁覆盖历史 2026-10-22 基准 JSON**。

对 QA/Agent 分开记录每道题的：
1. query、user-intended scope、检索配置 fingerprint / generation、分块方案、语料 SHA-256；
2. Top8/Top20 **实际有界候选**的 doc/chunk 身份、顺序、相似度与原因；无限/全量排名仅作独立诊断，**不能当成生产候选池**；
3. 上下文最终 source IDs、原始内容与截断后实际进入 Prompt 的片段、drop reasons（阈值/同文去重/跨文去重/来源名额/正文预算）；
4. agent 工具还要量出最终 600 字 quote 截断后的证据是否完整；
5. retrieval latency、index embedding 输入量、查询 embedding 调用数与输入量、context code points，模型实际/估算 tokens（有元数据时），成功/不确定与 Provider 失败分别记录。

必须重新执行 Q02/Q03/Q09 当前行为，并验证 **Q05（版本冲突）、Q07（缺证据拒答）、Q10（指定文档）、Q13（注入）** 和原有 E1–E11 退化反例。若 Q09 已自然恢复，不得为它引入额外生产修复。

### 2.1 统一诊断归因（分层、可组合）

- `INDEX_MISS`：应有正文未被有效解析、分块、索引或被 active generation/filter 排除。
- `RETRIEVAL_MISS`：文档与分块实际存在且可检索，但没有进入服务返回的有界候选 TopK。
- `SELECTION_MISS`：候选已在 TopK，但因相似度、去重、SOURCE_CAP 等未进入 source 列表。
- `BODY_TRUNCATION`：片段入选，但答案必需条件未出现在实际 Prompt/Quote 截断后文本。
- `GENERATION_FAILURE`：有效证据进入实际模型上下文，但回答遗漏、冲突或编造。
- `CITATION_FORMAT`：引用编号格式无效、分组表达不支持或映射错误。
- `CITATION_SUPPORT`：编号合法，但该引文不能支持对应事实结论。
- `PERSISTENCE_OR_ACCESS`：最终保存来源/正文身份错误、失效引用或越权。
- `PROVIDER_INCONCLUSIVE`：外部服务失败或无响应，不得冒充回答质量差/好。

**同一题允许主因+次因，必须说明所验证的阶段。**

## 3. D1 — 候选召回：仅在确认为瓶颈后实验

**不默认上新模型或换分块。** 保留生产 `DocumentChunker` **1200/150**、`DocumentSearchService` 的 topK 1–20 上限和 PostgreSQL provider/model/dimension/fingerprint/generation/READY/project/scope 过滤。基线先定位哪些问题在 TopK 外。

在测试工具侧评估最多两种具有业务意义、与金标准隔离的有界方案：
- **R0（对照）**：生产单次向量检索，保持原 Top8/Top20。
- **R1（实验）**：先做明确 query 的轻量语义分解或仅限用户问题中**明确可抽取的编号/术语**的受控精确候选补充；必须使用生产授权过滤、严格行数和查询数上界。中文词法检索、`pg_trgm` 或 PostgreSQL FTS 是否有效须真实测量，不可假定英文 FTS 的效果同样适用于中文。
- 如 R1 无可靠净收益，**停止该分支**。不默认进行无界改写、向量 N+1 搜索、重排模型下载、扩大到 Top100 或隐形收费的 query-expansion 调用。

允许提出另一个**有明确失败证据的受控替代实验**（例如限两个已命中的可信文档补读章节），但必须作为新对照明示查询与额外 I/O 成本。不能使用期望答案、标注文件、文档 ID 特判或 Q02 专用硬编码来选择候选。

评价不仅看 Recall@8/@20；同时看最终实际片段的 `Context Retention`、Document Coverage、每项重要事实保留、延迟及查询成本。**在 Q02 上仅提高 Top20 而不改善最终证据，不能称为质量成功。**

## 4. D2 — 上下文选择与预算：优先小改动、明确权衡

对 QA 的默认 5 来源路径与 Agent 8/12 来源路径分别建立实验。已经否决的 D/E 字面匹配仅作为历史反例对照，不作为新默认候选。

候选方向 **S1**：在 **不改变候选集合、用户 scope 和总正文预算** 前提下，对有明确分段结构的问题试验“跨来源代表 + 必要互补片段”选择；例如复合问题的独立子约束和已入选文档的不同章节。分段信号只能来自原始 query、受授权候选的 heading/content/score/identity，不得使用标注目标、预期答案或测试 ID。

设计硬约束：
- 保持每个来源真正的 `documentId + chunkId + contentHash + metadata`，同文跨文档不能因为正文一致而合并来源身份；同文档重复可以经测试后去重。
- Q05 当前 v2.1 修正不能被旧版或无关背景替换；Q09 双合同必须保留两份文档独立来源；Q03 上线/灰度/回滚必须逐项测量。源越权、失效、未 READY 一律禁止进入最终证据。
- 不能为了多塞“source”而把每份关键条件截断至不完整；在 **8000 code points**、每份最少正文及 Agent quote 600 字上限内逐项实测。
- 必须记录每一题的 gained/lost，包含“新方案救回了什么、又挤掉了什么”，而非只报总平均召回率。
- 对 E7/E11 / 两独立关键事实竞争同一名额的反例，新增规则若重要事实丢失即不准上线；来源数与独立文档覆盖不能无证据下降。
- 如果 5 来源结构上无法覆盖复合问题，允许**实验**明确较大来源数，但需同时测 Prompt 预算、模型回包和成本；不能只升 `DEFAULT_MAX_SOURCES` 就宣布质量改善。

**无合适无退化方案时维持生产 A，这也是合法的成功研究结论。**

## 5. D3 — 引用支持与答案端到端正确性

### 5.1 先分清机械校验与语义事实

当前 `KnowledgeCitationValidator` 可以按 `[S数字]` 做来源编号合法性校验并删除不存在的编号；**它不能证明某个句子被来源语义支持，也不能从混合回答中可靠判断“部分资料不足”**。历史 Q03/Q09 的 `[S1, S2, ...]` 分组引用格式也未按单项引用模式独立解析。

建议最小改造候选：
1. 增加**受限引用格式解析**的确定性用例：合法 `[S1]`、连续 `[S1][S2]`、分组 `[S1, S2]`、重复、空、恶意/畸形引用，避免合法分组下没有对应持久化引用；跨项目与未入上下文来源不可通过。
2. 将机器可判定的 `citation_number_valid` 与人工审查的 `citation_claim_supported` 明确分离。不能通过编号清理把无依据事实变成“已验证答案”。
3. `insufficientEvidence` 当前是**狭义结构标记**而非逐事实完整性的语义证明；若要扩展，必须有独立、可测试且不会错把部分可答判成全部无答的契约设计。本次可以先对该边界补用例/文档，**不要靠关键词“资料不足”粗暴改业务状态**。
4. 问题无法完整回答时，要求最终用户文案明确缺失范围，不能把模型诚实拒答计为任务完整性成功。
5. 独立审查实际 source 提供的事实是否支持结论，不用被评估模型给自己打分。

### 5.2 真实模型实验限制

历史 Gemini 已使用用户给定的 **24/24** 调用预算。**本任务默认不执行任何新的外部 Gemini/付费 Provider 请求**。如果未来用户明确批准新的调用额度、模型和测试范围，按已有 opt-in 机制重新使用冻结 Q01–Q16；明确回答题、SSE 题、Agent-loop 题分别记账，Provider 不可用记 `INCONCLUSIVE/NOT_EXECUTED`，不自动反复重试或悄悄切模型。

外部模型未授权时，D3 仍可交付可重复的源码单测、pgvector 检索和引用持久化实测、人工标注的**已保存历史真实输出回放**。历史回放不是新模型生成，也不能声称新策略的实时回答质量已验证。

## 6. D4 — 成本与性能口径

至少分别报告：

| 维度 | 基线/候选可比口径 | 严禁的伪结论 |
|---|---|---|
| 候选召回 | 同 query、同文档、同 generation、同 Embedding、同 topK、同 scope 下 Recall@8/@20 | 用全量排序回填生产 Top20 或混入文档限定范围 |
| 最终证据 | **实际**进入 Prompt/Agent 600字 quote 的必需事实数、独立文档覆盖、source 数、丢弃原因 | 只看被检索到的 chunkId，不查文本是否被截断 |
| Embedding | 索引输入 code points/估算 token、实际向量请求数、cache hits（可得时）、查询向量调用数 | 把测试缓存命中顺序当成无缓存生产成本 |
| 生成 | 真实 API 返回 input/output tokens 与 usageBasis，未知为 N/A；所选模型/调用次数/429/超时 | 无用量时记作 0、免费就推断费用为 0 |
| 延迟 | 预热/冷启动分开、后端 pgvector 搜索与模型网络时长分开、多次成对实验 | 1 次短测就承诺 P95 改善 |
| 变更风险 | 重建 chunk 数量、identity、旧 READY 文档兼容、历史 knowledge_citation 外键级联删除风险 | “换参数无需改迁移”推导成“重索引无引用损失” |

如无真实 Provider 生成，最终汇总需拆分 `RETRIEVAL_QUALITY_RESULT`、`CITATION_CONTRACT_RESULT`、`END_TO_END_GENERATION_RESULT`，不得一项通过掩盖另一项未执行。

## 7. 生产准入硬门槛

**先设门槛再跑实验，不在看到结果后反向放宽。**

1. 原冻结 QA 16 道/14 道历史真实回放与真实 Ollama 主样本全部保留（如新增题，单列新集），项目隔离、指定文档范围、Embedding 指纹/代际为**零越界**。
2. 原有已通过的**重要事实**（版本优先级、权限、限额数字、审批主体和负条件）**零回归**。多源事实须完整保留，不能用提升均值抵消丢失关键事实。
3. 目标失败用例存在可见、可重复的改善：Q02 必需事实进入最终有效 Prompt/Quote；Q03 上线门禁真实保留；Q09 两个独立来源身份均保留（若当前基线已修好，只须保持）。至少出现一项原始核心缺口的端到端**证据保留**改善，且重要事实零回归，才建议任何生产选择策略改动。
4. 对相同样本，候选方案总候选/查询调用、code points、延迟、额外模型调用与资源成本必须有上界；若显著更贵需要明确价值证明和接受限制，不能为了召回无界扩张。
5. 引用编号/身份/持久化、权限、提示注入、无证据拒答（机械契约可检验的部分）、SSE/Agent 已有回归不得倒退。
6. **仅在有授权的真实 Provider 端到端实验通过人工评分时，才能宣称 `REAL_ANSWER_QUALITY_ACCEPTED`。** 无新调用授权，最多宣称检索/引用契约通过，不得捏造生成层收益。
7. 不修改生产分块器/索引策略前必须有针对历史引用和重索引影响的额外证据及可回退方案。禁改历史 Flyway；新版本按当时最新版本连续追加。

允许三个最终结论：
- `P6_RAG_QUALITY_01_ACCEPTED`：设计范围内的已授权阶段及所要求的实测门槛全部满足，明确写出哪些是真实模型、哪些只是检索/离线。
- `P6_RAG_QUALITY_01_NO_CHANGE_EVIDENCE_INSUFFICIENT`：完成足够可复核实验但未证明候选具备安全的独立净收益，维持生产策略；**是合格研究交付，不是假装功能优化成功**。
- `P6_RAG_QUALITY_01_NOT_ACCEPTED`：关键测试、事务/权限、证据完整性或最终 CI 失败。

## 8. 推荐实施顺序（避免大改与 CI 等待）

| 步骤 | 交付 | 验收焦点 |
|---|---|---|
| **D0：冻结现状** | 只读审查、当前 dev 的 Q02/Q03/Q09 与 E1–E11、原始基线和分层误差表 | 不修改任何原始标签、历史快照，不依赖外部 Chat |
| **D1：受控候选实验** | R0/R1 的固定样本、pgvector 真实测试与成本/越界报告 | 有界查询，真实 TopK，明确有无召回净收益 |
| **D2：受控上下文实验** | QA 5 来源与 Agent 8/12 来源各自对照、反例矩阵 | 保留关键事实/来源身份、预算与 query scope |
| **D3：引用契约** | 确定性分组引用/异常引用测试，引用支持人工审核流程 | 引用编号合法 != 事实受支持；不足标记不冒充语义检测 |
| **D4：准入与交付** | 明确 NO_CHANGE 或小改代码、定向+全量回归、文档/证据与 CI | 可独立核对最终 SHA、真实数据、准入决策和不确定性 |

CI：日常仅跑定向测试；数据库检索和引用改动要跑相关 Testcontainers；专项收口跑完整后端 `bash ./mvnw -B -ntp verify`（存在完整 Docker socket 的终端）；无前端改动不重复跑本地前端全套。提交 `dev` 正常触发 CI，**每个中间提交不必等待**；正式验收查最终 SHA 的 Push/PR CI。真实 Ollama/Gemini opt-in 被跳过时，不冒充执行。

## 9. 验收矩阵（示例 ID）

| 编号 | 真实验收断言 |
|---|---|
| D01 | 当前 `dev` 基线、原始 SHA/hashes、冻结题集、工具/QA 不同 TopK/来源限额实核 |
|---|---|
| D02 | Q02 归因到当下候选 TopK/上下文/工具 quote 哪一阶段，不能用历史推断 |
| D03 | Q03 已进入候选但掉出最终来源时能够记录 SOURCE_CAP 与关键事实损失 |
| D04 | Q09 当前跨文档相同 hash 的两个独立 docId 是否都保留；不重复修旧 bug |
| D05 | 真实 pgvector + 本地 Ollama（有明确启用时）对照；未启用则明确 `NOT_EXECUTED` |
| D06 | D/E 和 E7/E11 关键退化反例及生产策略 A 的确定性一致性持续通过 |
| D07 | 新候选/选择策略严格限定项目、用户允许的文档、READY、fingerprint、generation；禁止越界 |
| D08 | QA 与 Agent 不混分母；真实 Prompt 与工具 quote 600 截断完整事实逐项验证 |
| D09 | source=doc/chunk/hash/metadata 身份与引用一致，不以同号旧 chunk 冒充新版 |
| D10 | 引用单项、连续、分组、重复、非法、越界、恶意文本等机械契约测试 |
| D11 | 非完整回答、缺证据、冲突版本、相同条款不同文件的语义审核与不足标记边界清楚 |
| D12 | 原核心已通过事实 0 回归，逐项 gained/lost，不用均值掩盖损失 |
| D13 | 候选池/查询数/正文预算/用量/延迟有界，真实与估算/缓存与非缓存分开报告 |
| D14 | 有索引策略或分块变更时评估旧 READY / 重索引 / knowledge_citation 历史引用级联风险 |
| D15 | 无用户新授权不调用外部 Gemini，不读取私密凭据，不修改真实项目数据 |
| D16 | 定向与完整后端 verify，真实容器测试执行；最终同 SHA CI 状态与证据一致 |

## 10. 文档落位与仓库红线

按 `AGENTS.md` 第 9 节和 `docs/README.md`：

- 长期设计：`docs/design/rag-quality-and-evidence.md`；更新 `docs/README.md` 导航。
- 实验研究/反例解释：`docs/archive/research/p6-rag-quality-01-research-YYYYMMDD.md`。
- 阶段交付报告：`docs/archive/reports/p6-rag-quality-01-delivery-YYYYMMDD.md`。
- 复核：`docs/archive/reviews/`。
- 证据：`docs/acceptance-evidence/2026-10-25/p6-rag-quality-01/`；只提交脱敏的可复核结果。
- 新增标准公开合成语料：`tests/fixtures/documents/`，同步登记 `tests/fixtures/documents/README.md`。旧语料和旧基准 JSON **只读、不覆盖、不改名**。
- 测试原始输出：`ai-collab-backend/target/`；不在项目根目录生成临时日志或私密 API 凭据。

必须保留 `main` 不变；PR #18 持续 OPEN/Draft/未合并，不 force push，不覆盖用户 WIP，不为了得到 ACCEPTED 而放宽测试或制造假样本。

## 11. 本设计依赖的关键路径

1. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/DocumentSearchService.java`
2. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/domain/service/DocumentChunker.java`
3. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeContextBuilder.java`
4. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/CrossDocumentSourceSelector.java`
5. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/service/KnowledgeCitationValidator.java`
6. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/knowledge/application/service/KnowledgeQuestionApplicationService.java`
7. `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/KnowledgeSearchAgentTool.java`
8. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/AgentRetrievalQualityPostgresTest.java`
9. `ai-collab-backend/src/test/java/com/shitulelv/aicollab/retrieval/GeminiKnowledgeAnswerQualityPostgresTest.java`
10. `docs/acceptance-evidence/2026-10-22/p4-rag-quality-02/README.md`
11. `docs/acceptance-evidence/2026-10-22/p4-rag-quality-03/README.md`
12. `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/network-recovery-03/README.md`
13. `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/rubric.md`
14. `docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/questions.json`

实际实施前先在当前 `dev` 确认上述文件没有被后续改动改变契约。若关键事实与设计冲突，以当前生产源码和原始试验数据为准，并修订设计中的相应描述，不要悄悄修改冻结标签。
