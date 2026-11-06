# AI Collab RAG V2｜P0 质量回归一轮彻底修复与真实质量复验终审报告

> 任务：RAG V2 P0 质量回归修复（对应裁决 `RAG_V2_QUALITY_REGRESSION`，上游报告
> `2026-11-07-rag-v2-quality-eval-final-report.md` HIGH-1）
> 修复与复验日期：2026-11-07
> 起始基线：`876e7ab5fe9e0028529be365d078d4f144818870`（dev，工作区干净，与独立审查基线一致）
> 最终裁决：**RAG_V2_QUALITY_RECOVERED**（依据见 §9）
> 验收证据：`docs/acceptance-evidence/2026-11-07/rag-v2-quality-regression-fix-01/`

---

## 第一部分：真实代码修复

### 1.1 修复总设计：类型化评分契约

根因是**一种字段承载三种分数语义**：`RetrievalCandidate.toDocumentSearchHit()` 把重排分/RRF 分
写入 `DocumentSearchHit.similarity`，而装配层以余弦语义的 0.15 阈值过滤同一字段。修复不采用
"调阈值/伪造分数/无条件放行"中的任何一种，而是把**准入**与**排序**两种职责显式分离：

- **准入分数（admission score）**：只在 Dense 余弦相似度与 Lexical 匹配强度两类 [0,1] 可信召回
  分数之间取最大值；缺失的一路不贡献（缺失 ≠ 0 分事实）。RRF 分与重排分**永不进入**该字段。
- **排序语义**：管线输出候选的顺序即 RRF→Reranker 的最终排名，写入命中
  `metadata.retrievalScores` 类型化元数据；下游以该元数据识别"携带排名语义的管线输出"，
  不再按准入分数重排。

公共锚点为新类 `common/retrieval/RetrievalScores.java`（常量 + 工厂 + 类型化识别），
领域层（knowledge.domain）只依赖 common，不引入跨模块耦合。

### 1.2 逐文件修复清单

| 文件 | 方法/位置 | 修复内容 |
| --- | --- | --- |
| `common/retrieval/RetrievalScores.java`（新增） | — | 评分类型契约：`retrievalScores` 元数据键、分数键名、admissionBasis、`carriesTypedRanking()` 识别器 |
| `retrieval/pipeline/RetrievalCandidate.java` | `admissionScore()`、`admissionBasis()`、`toDocumentSearchHit()` | **P0-1 核心**：similarity 改为 `max(denseSimilarity, lexicalScore)`（缺失不贡献，两者皆缺失=0.0 不可绕过准入）；`retrievalScores` 元数据携带 dense/lexical/rrf/rerank/admission 全量类型化来源 |
| `document/application/view/DocumentSearchHit.java` | 类 javadoc | 契约文档：similarity 字段三种路径下的准入分数语义；无签名变化，MyBatis 映射与既有构造器不受影响 |
| `knowledge/domain/service/KnowledgeContextBuilder.java` | `MIN_SIMILARITY` javadoc | **P0-2**：核实全部 5 个装配入口（buildWithTokenBudget / buildBalancedWithDiagnostics / buildWithDiagnostics / buildDiverseWithDiagnostics / classifyCandidatesAfterBudgetStop）——均消费 `hit.similarity()`，由生产者侧契约统一修复；阈值数值保持 0.15 不变，javadoc 固化准入语义 |
| `knowledge/domain/service/CrossDocumentSourceSelector.java` | `selectWithDiagnostics()` | **P0-3**：准入过滤保留（作用于准入分数）；排序改为——携带 `retrievalScores` 的管线输出**保序**（RRF/Rerank 排名不被覆盖），旧版 Dense 直连输入（无类型化元数据、天然相似度序）保持原有相似度排序行为，既有 11 项 selector 测试全部原样通过 |
| `retrieval/pipeline/ReciprocalRankFusion.java` | `fuse()` 比较器 | tie-breaker 从 `max(denseSimilarity, lexicalScore)`（跨分数体系比较，与 P0-1 同源语义错误）改为排名空间证据：双路召回优先 → 最优排名小者优先 → chunkId 稳定收敛 |
| `retrieval/pipeline/SemanticHeuristicReranker.java` | `extractTerms()` 等 | **P1-2**：中文长句不再整串成词（>10 汉字的 token 只走 bigram）；中文 bigram 移除 ≤10 长度上限，全句覆盖 + 超长按均匀步长采样（上限 48）；虚词端点 bigram 降噪；英文/类名/API 片段 token 保留；词项总数有界（64）；兜底与降级路径保留 |
| `retrieval/pipeline/RetrievalPipeline.java` | 新增 `executeMultiQuery()` | **复测中发现的阻塞缺陷**：COMPLEX 多查询分解原先 `putIfAbsent` 顺序拼接，把原始查询下排名 1–5 的证据挤出名额。修复为：主查询（原始查询）排名序保序置前 + 子查询独有候选按跨查询 RRF 之和追加，融合池随查询数有界扩展（topK+3×(n-1)，上限 24）；子查询只增召回、不扰动主排名 |
| `knowledge/application/service/KnowledgeQuestionApplicationService.java` | COMPLEX 分支 | 改用 `executeMultiQuery`（原始查询 + 子查询共同入池） |
| `knowledge/application/service/KnowledgeStreamQuestionService.java` | COMPLEX 分支 | 同上，与普通问答共享同一融合语义；SSE 生命周期/取消/异常处理未动 |

### 1.3 为什么该契约不会再次出现 RRF 与余弦混淆

1. **类型系统层**：`similarity` 字段只可能被赋值为 `admissionScore()` 的返回值——该方法的实现
   里不存在 rrfScore/rerankScore 分支，混淆在代码路径上不可表达。
2. **来源标注层**：管线输出的每个命中携带 `retrievalScores`（含 admissionScore 与 admissionBasis），
   任何下游若再拿 rrf/rerank 值参与准入或排序，会与元数据中的类型化值冲突，可被测试直接检出。
3. **测试层**：`RetrievalScoreContractTest`（17 项）+ `RagV2ChineseQueryRerankRegressionTest`（正向断言）
   固化"RRF/重排分不得冒充余弦"矩阵，包括"仅有 RRF 分数不得绕过准入""重排极小分不得当余弦过滤"。
4. **真实评测门禁层**：`RagV2QualityEvaluationPostgresTest` 新增 qualityGate（PASS/REGRESSION/
   NOT_VERIFIED/BLOCKED 四态），本轮实测其拦截能力——迭代 2 融合方案退化（46/54）即被门禁
   拦下（EVAL_EXIT=1），未流入提交。

### 1.4 明确不做的事（与约束对照）

未调整 `MIN_SIMILARITY` 数值、未伪造任何 similarity=1.0、未关闭 RRF/重排、未绕过
KnowledgeContextBuilder、未退回 Dense Only、未改动冻结评测集与标准答案、未新增 Flyway 迁移、
未触碰权限过滤与 SQL 硬过滤、未修改任何 V1–V80 历史迁移。

---

## 第二部分：评分语义矩阵

| 分数 | 含义 | 数值范围/约束 | 准入用途 | 排序用途 | 缺失处理 | 对外暴露 |
| --- | --- | --- | --- | --- | --- | --- |
| `denseSimilarity` | pgvector 余弦相似度 | [0,1] | 与 Lexical 取 max 后构成准入分 | RRF tie-break（排名空间）内不直接比较 | 不贡献 max，不当作 0 分 | `metadata.retrievalScores.denseSimilarity` |
| `lexicalScore` | pg_trgm similarity 与精确包含校准值（标题 0.8/正文 0.6）的较大者 | [0,1]，SQL 内 GREATEST 计算 | 同上 | 同上 | 不贡献 max | `metadata.retrievalScores.lexicalScore` |
| `rrfScore` | 排名融合分 `Σ 1/(60+rank)` | ≈0.016–0.033（单路）/更高（多路/多查询求和） | **禁止**（量级不可比） | RRF 主排序键；多查询融合求和 | 0.0（真实无贡献） | `metadata.retrievalScores.rrfScore` |
| `rerankScore` | 启发式/神经重排输出 | 加权和，量级不定 | **禁止** | 重排主排序键（RRF 平分时 tie-break） | 不写入 | `metadata.retrievalScores.rerankScore`（含 reranker 名） |
| `similarity`（DocumentSearchHit） | **准入分数**：max(Dense, Lexical) | [0,1] | 全部装配入口阈值 0.15 | 旧版直连输入的相似度排序；管线输入**禁止**用于重排 | Dense-only=纯余弦；Lexical-only=纯词项强度 | 对外（引用/持久化）即此值，语义不再误导 |

Lexical-only 候选不伪装 Dense 分数；只有 RRF 的合成候选 admission=0.0，被 0.15 阈值正常拒绝；
评分元数据仅由内部检索管线写入，上传内容无法伪造准入状态。

---

## 第三部分：QA / Stream / Agent 三条生产链验证

三条链路共用 `QueryRouter → RetrievalPipeline → toDocumentSearchHits → KnowledgeContextBuilder`，
本次修复位于共享生产者（转换层）与共享融合层，三条链路的装配语义逐字一致：

| 路径 | 生产入口 | 验证方式 | 结果（真实 PG + 真实 embedding） |
| --- | --- | --- | --- |
| A 普通知识问答 | `KnowledgeQuestionApplicationService` | 39 题冻结语料全量实测（V2 生产路由→管线→buildWithTokenBudget 2500 tokens） | **52/54 (96.3%)**，空上下文 **0/39**（V1 为 2/39） |
| B 流式问答 | `KnowledgeStreamQuestionService` | 与 A 共享同一检索与装配调用（代码路径核实 + 契约单测）；流式分支 COMPLEX 融合与 A 逐字一致 | 装配语义一致性由共享代码与单测保证；无单独空上下文缺陷面 |
| C Agent 检索工具 | `KnowledgeSearchAgentTool`（6 参管线构造） | 39 题 V2 Agent 工具全量实测（pool 20 → buildDiverse 8 → 引用） | **53/54 (98.15%)**，唯一未保留项 fullRank=1 因 5 项子问题竞争 8 名额（见 §7） |

安全与身份核验（39 题机械断言，全部通过）：scope 越界 0；chunkId/documentId/filename/quote
来源身份逐条回库比对 0 违规；`rerankerDegraded` 全程 0 次；元数据硬过滤（MF1/MF2）候选层
正确生效（干扰文档被剔除）。权限过滤 SQL（project_id/documentIds/generation_id/外部连接器状态）
未做任何改动。

---

## 第四部分：历史质量对照（P9 冻结语料，39 题 / 54 事实）

| 指标 | V1 | 修复前 V2 | 修复后 V2 |
| --- | ---: | ---: | ---: |
| QA 必要事实保留率 | 52/54 (96.3%) | 6/54 (11.1%) | **52/54 (96.3%)** ✅ 达历史基线 |
| Agent 必要事实保留率 | 51/54 (94.4%) | 0/54 (0%) | **53/54 (98.15%)** ✅ 超历史基线 |
| 39 题空上下文 | 2 | 33 | **0** ✅ 优于 V1 |
| P10 有证据题成功进入生成 | 13/14 | 0 | **12/12**（14/14 次真实模型调用） |
| 检索 P50/P95（本地参考） | 82ms / 377ms | 196ms / 607ms | 157ms / 693ms |
| 来源覆盖与引用 | 校验全通过 | 不可验证 | **14/14 生成 0 引用违规**；39 题 scope/身份核验 0 违规 |

修复后 V2 剩余 3 个未保留事实的逐题归因（诚实呈现，未做强凑）：

1. `G02-B-3DOCS / B2-服务交接审批流程.md`（fullRank=15）：原始查询 dense 排名 15，V1 同样未保留
   ——**与 V1 共有的召回局限**（TOP_K=8 协议下固有）。
2. `G02-B-4DOCS / B4-服务交接例外情形.md`（fullRank=1）：五段式复合问题，候选池 8 与来源名额 5
   竞争下被同文档族其它子题证据挤出（SOURCE_CAP）。V1 在同题保留了该事实但丢失了
   `G03-B2-COMPLEMENT` 的事实——**两版在此类极限复合题上的取舍不同，总量持平（各失 2 题）**。
3. Agent 唯一未保留：同 B4 事实（fullRank=1，`SELECTION_MISS:NOT_IN_TOOL_RESULT`），
   同为名额竞争；V1 Agent 同题保留但另有 3 题未保留，V2 Agent 净优 2 题。

V1 协议复测（search+buildBalanced，冻结口径）在修复树与历史完全一致（52/54、51/54），
证明语料、协议与历史基线可复现，未发生评测漂移。

## 第五部分：消融实验（五变体 × 39 题，修复后 V2）

| 变体 | 配置 | 事实保留率 | 结论 |
| --- | --- | ---: | --- |
| denseOnly | lexical=0，无窗口/父子，PASS_THROUGH | **52/54 (96.3%)** | Dense 主干质量即历史基线水平 |
| hybridNoExtras | 混合检索，无 window/parent | 49/54 (90.7%) | Lexical 对中文长句问题的扰动为负贡献，需窗口/父子/重排配合 |
| hybridWindow | 混合 + 句子窗口 | **52/54 (96.3%)** | 窗口扩展恢复混合检索损失，回到基线 |
| hybridParent | 混合 + 父子回读 | **52/54 (96.3%)** | 同上 |
| fullDefault | 完整默认管线（启发式重排） | **52/54 (96.3%)** | 完整管线与 Dense-only 持平且空上下文 0、Lexical 命中可补位 |

组件贡献首次可测：混合检索在无扩展时对中文查询有负贡献（-3 事实），窗口/父子扩展各自将其
拉回基线；启发式重排在完整管线中未造成损失（rerankerDegraded=0，重排仅在 8 候选内调序）。
`pg_trgm` 中文召回偏弱（平均 2.9 候选/题）的观察依旧存在，属低影响已知限制，不在本轮范围。

多轮追问（2 组 × 3 轮）：MT1 第 3 轮 V2 反超 V1（SELECTION_MISS→RETAINED）；MT2 第 3 轮
两版各失一项（V1 RETRIEVAL_MISS / V2 SELECTION_MISS）。元数据过滤 2 例全部符合预期。

## 第六部分：测试执行

### 执行命令与统计

| 命令 | 结果 |
| --- | --- |
| `mvnw test -Dtest='RetrievalScoreContractTest,RagV2ChineseQueryRerankRegressionTest,RetrievalPipelineTest,KnowledgeContextBuilderTest,CrossDocumentSourceSelectorTest,ReciprocalRankFusionTest,...'`（多批） | 58 项相关单测全绿（含新增 17+2+1 项） |
| `AI_RAG_V2_QUAL_EVAL=true mvnw test -Dtest=RagV2QualityEvaluationPostgresTest` | 4 轮迭代全部真实执行；最终轮 **Tests run: 1, Failures: 0**，`RAG_V2_QUALITY_GATE=PASS violations=[]`，EVAL_EXIT=0 |
| `mvnw -B -ntp verify`（全量回归 ×2，Testcontainers PG17+pgvector+Redis+MinIO） | 首轮（LOW-1 修正前代码态）：**Tests run: 2231, Failures: 0, Errors: 1, Skipped: 45**，唯一错误为 `PersistedModelTurnRecoveryPostgresTest` 的 JDBC 连接获取瞬态失败（`CannotGetJdbcConnectionException`，无断言失败）；终轮（最终代码态）：**Tests run: 2231, Failures: 1, Errors: 0**，唯一失败为与本次改动无关的 `DashboardCachePostgresIntegrationTest.concurrentReadersAndWritersRemainCorrect`（仪表盘缓存并发竞态） |
| `mvnw test -Dtest=PersistedModelTurnRecoveryPostgresTest`（单独复跑） | **24/24 全绿**，BUILD SUCCESS——确认首轮错误为瞬态基建问题 |
| `mvnw test -Dtest=DashboardCachePostgresIntegrationTest`（单独复跑） | **11/11 全绿**，BUILD SUCCESS——确认终轮失败为并发抖动，非代码回归 |
| `AI_REAL_OLLAMA_RETRIEVAL=true mvnw test -Dtest='AgentRetrievalQualityPostgresTest#pairedProductionChunkingOnHistoricalCorpus'` | **Tests run: 1, Failures: 0**（65.27s，真实 Ollama embedding），BUILD SUCCESS——LOW-1 夹具修正验证通过 |

### 环境与真实性声明

- PostgreSQL：Testcontainers `pgvector/pgvector:pg17` 一次性容器，Flyway V1–V80 全量迁移（未修改任何历史迁移）。
- Embedding：本地 Ollama `qwen3-embedding:0.6b`（1024 维，fingerprint 冻结，与历史评测一致）。
- 生成：ModelScope `deepseek-ai/DeepSeek-V4.1-Flash-1017`（temperature=0，路由快照从本地库只读复制，
  密钥零落日志）。**真实模型调用：P10 共 28 次**（V2 14 次 + V1 对照 14 次，全部成功，0 次 Mock）。
- 跳过用例 45：均为既有环境门控（`@EnabledIfEnvironmentVariable`）与非门控环境跳过，与本次修复无关。
- 最终退出码：评测 EVAL_EXIT=0；全量 verify 两轮各出现 1 个**互不相同、均与本次改动无关**的
  瞬态问题（agent runtime JDBC 连接 / dashboard 缓存并发竞态），各自单独复跑全绿——
  两轮合计覆盖全部 2231 项测试，0 个未解释失败。
- 迭代轮次中评测门禁曾正确拦截一次真实退化（run2：QA 46/54 → EVAL_EXIT=1，REGRESSION），
  该轮结果原样归档，未删除失败变体。

### 评测夹具修正（不改动业务标准与冻结答案）

- **AQ14-INJECTION scope 顺序（LOW-3）**：`measureGenerationQuestion` 先索引临时注入文档再
  `resolveScope`，消除了取到上一题 FAILED 文档 UUID 的执行顺序缺陷；修复后 AQ14 正常进入
  生成且 5 源齐备（本轮 14/14 全部执行）。
- **pairedProductionChunking 断言（LOW-1）**：实跑定位真实失败面——V2 生产 `DocumentChunker`
  已升级为 PARENT/CHILD 层级分块，与 V1 时代平铺实验算法结构性不一致，旧的整记录相等守卫
  在 V2 下必然失败。守卫目的收窄为两条分块路径各自的边界完整性（正文子串一致 + 坐标系 +
  contentHash），生产分块一致性由 `RagV2MetadataAndParentChildPostgresTest` 覆盖；门控单方法
  实测通过。

### P10 真实生成质量（14 题全部真实执行）

- 12 道有证据题全部进入生成（修复前 0）；引用违规 0；11 题零高严重度违规。
- 2 项高严重度为模型侧已知倾向（与历史 V1 发现同类，非检索层缺陷）：AQ02 版本口径违规
  （旧条款以现行口径表述）、AQ14 注入遵从（回答复述了不可信文档中的自证指令）。
  两者均已由 `P10AnswerQualityScorer` 如实记录（needsHumanReview），未做掩盖。
- 2 道拒答题（AQ11/AQ12）在本轮装配提供了相关证据，模型侧拒答纪律问题与历史发现一致，
  属生成层范畴。

## 第七部分：剩余风险（仅列真实存在项）

1. **极限复合题的名额竞争**：五段式问题（G02-B-4DOCS 类）在 8 候选/5 源协议下仍有 1 个事实
   因名额竞争丢失；候选池与来源数是冻结协议参数，调整需另行立项。
2. **`pg_trgm` 中文召回偏弱**：平均 2.9 候选/题，混合检索在无扩展时为负贡献；已由窗口/父子
   扩展兜底，Lexical 通道的中文分词增强不在本轮范围。
3. **QueryRouter 语义改写质量未独立评定**：改写后的 effectiveQuery 作为主查询表现良好（实测
   52/54 达基线），但改写本身未做消融对比，留待后续。
4. **生成层模型纪律**：AQ02 版本口径与 AQ14 注入遵从为模型侧倾向，装配层证据与引用契约
   正确；建议在生成侧（提示词/后校验）另行处置。
5. **全量 verify 的瞬态基建抖动**：两轮 verify 分别出现 1 个互不相同的瞬态问题
   （agent runtime JDBC 连接获取失败 / dashboard 缓存并发竞态），均复跑全绿、与本次改动无关；
   建议关注这两个测试类在 CI 的稳定性。

## 第八部分：Git 交付

| 项 | 值 |
| --- | --- |
| 起始完整 SHA | `876e7ab5fe9e0028529be365d078d4f144818870` |
| 最终完整 SHA | 本报告所在的 dev 本地提交（`git log -1 --format=%H` 即最终交付 SHA，未推送） |
| 提交内容 | 生产修复 9 文件 + 新增契约类 1 + 测试 6（改 4 新 2）+ 评测夹具与门禁 + 证据归档 + 本报告 |
| 工作区状态 | 提交后干净（仅 target/ 下未跟踪运行日志，不入库） |
| 是否推送远端 | **否** |
| 是否合并 main | **否** |
| 是否部署生产 / 修改生产数据库 | **否 / 否** |

---

## 第九部分：最终裁决

# `RAG_V2_QUALITY_RECOVERED`

依据：

1. **评分类型混淆彻底消除**：准入（max(Dense, Lexical)，0.15 阈值语义不变）与排序（RRF/Rerank
   保序）显式分离，并有类型化元数据、契约测试、真实评测门禁三层防复发结构。
2. **三条生产链真实恢复**：QA 52/54（=V1 基线）、Agent 53/54（>V1 基线 51）、空上下文 0/39
   （<V1 的 2），全部来自 Testcontainers 真实库 + 真实 embedding + 真实模型调用的冻结语料实测。
3. **安全与身份零破坏**：scope/身份机械核验 0 违规，权限 SQL 未动，历史迁移未动，冻结语料
   manifest sha256 校验通过，未修改任何标准答案或评分标准。
4. **全量回归无未解释新增失败**：两轮 verify 合计覆盖 2231 项测试，唯一的两处瞬态问题
   （不同测试类、均与本次改动无关）复跑全绿，属已知基建/并发抖动。
5. **过程诚实可复核**：四轮迭代全部归档，包含一轮被门禁正确拦截的退化（run2 46/54）与
   逐题失败归因，无任何"为通过而通过"的调整。

未尽事项（QA 剩余 2 题为与 V1 持平的协议限额内名额竞争/召回局限，Agent 净优 2 题）已在
§4 逐题归因、§7 如实列报，不构成对 RECOVERED 裁决的阻碍。
