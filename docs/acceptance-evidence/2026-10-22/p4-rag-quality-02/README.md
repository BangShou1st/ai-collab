# P4-RAG-QUALITY-02：长文档召回与生产分块准入复验

最终判断：**NO_CHANGE_EVIDENCE_INSUFFICIENT**。生产继续使用 **1200/150**。

在相同的 13 篇重建文档、相同问题与本地真实 Embedding 下，旧参数的尾部证据未进入 Top 20；800/120 将其带到第 17 名，但最终上下文仍未保留。阶段一“旧参数漏召回 → 新参数召回且保留”的联合门槛未通过。测试通过只证明实验执行和机械不变量成立，不能据此批准生产修改。

## Git 与改动范围

- 起始分支：dev；HEAD 与 fetch 后 origin/dev 均为 22b46016df99439c861caf0d5b252e0b863073d2；起始工作区 clean。
- main 与 origin/main 均为 9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7；本轮不切换、不修改、不合并 main。
- 结束提交：承载本报告的 dev 提交（使用 git log -1 --format=%H -- docs/acceptance-evidence/2026-10-22/p4-rag-quality-02/README.md 可得精确 SHA）。普通推送后的精确 HEAD、origin/dev、工作区及最终 SHA 对应 CI Run/attempt/status 另写入[本地交付回执](../../../../ai-collab-backend/target/retrieval-baseline/p4-rag-quality-02/delivery-receipt.json)，并在聊天交付中明确列出，避免提交内自引用 SHA。
- 生产文件改动列表：**空**。DocumentChunker、SOURCE_CAP、阈值、Provider、Agent Runtime、前端及 Flyway 均无修改。
- 测试侧复用 AgentRetrievalQualityPostgresTest 原始种子和问题，新增一项 opt-in 成对复验；复用既有 ParametricChunker，在全部重建文本上断言其 1200/150 输出与真实生产分块完全一致。
- 移除上一轮实验 JSON 中写死的虚构成功叙事，改为要求查看实际逐题测量；旧报告以增补形式更正过度结论，保留历史记录。

## 原始 DB_MISS 与可比性

历史问题：**继续发布需要满足什么前提条件？**。期望来源为《发布回滚预案.md》预制 chunkNo=3“续发前提”，正文末尾为“关键前提：只有最近一次回滚演练通过的项目才允许继续发布。”。其前置演练步骤重复 20 次，语义信号被稀释，真实向量排名在 Top 20 之外；不是上下文截断，也不是 Top8 窗口丢弃。

历史种子由测试直接插入，并非生产分块结果。HISTORICAL_PREBUILT 保留全部 13 文档 / 38 预制分块，只作历史参考。A/B 则将**全部**原始种子的 heading/body 重建为 Markdown 章节，同一文档各章节用两个换行连接；没有删除长文附录、性能测试八条噪声、旧版本或任何竞争文本。历史测试夹具未提供原始上传文件，因此这是一组从完整种子正文重建的可比实验，不冒充历史原件。

13 篇文档为：上线验收规程、发布回滚预案、任务预算规则、批量导出规范、租户并发限制、合同模板甲、合同模板乙、需求变更单、容量与性能约束、安全与脱敏规范、数据归档通告、检索性能测试手册、长文附录。每篇文件的 SHA-256、完整分块正文/标题/坐标及逐题目标见 [paired-chunking.json](paired-chunking.json)，均为仓库公开合成语料。

Embedding 固定本地 Ollama qwen3-embedding:0.6b，1024 维，fingerprint=20f29e8d94afc1eb2f5de49d3f4c81f548881de08e7aa4acb955ec87f5face75。数据库为一次性 Testcontainers pgvector/pgvector:pg17，使用生产 DocumentSearchService → Repository → Mapper SQL，项目条件、模型/指纹/代际过滤不变。每组重置隔离数据库并使用等价的独立项目，不接触真实项目。

TopK 固定 20，Recall@8 取同次排序前八项；上下文始终仅使用这 20 项，SOURCE_CAP 固定生产策略 A、12 来源、8000 code points。另取全量排序仅诊断未入选目标的实际排名和相似度，**从不回填上下文或 Recall@20**。

| 数据形态 | 候选总分块 | 原始尾部问题全量排名 | 相似度 | Top8 / Top20 | 最终保留 |
| --- | ---: | ---: | ---: | --- | --- |
| 历史预制参考 | 38 | 23 | 0.364258 | 0 / 0 | 0 |
| A 1200/150 | 47 | 23 | 0.364258 | 0 / 0 | 0 |
| B 800/120 | 53 | 17 | 0.416180 | 0 / 1 | 0 |

候选池从 47 增到 53 块，膨胀 **12.77%**。主要增加来自长文附录切分；尾部条件也从约 808 字的整段变为约 148 字的尾块。不能把更细分的竞争池假装成固定 38 块。B 的目标即使进入 Top20，仍因生产覆盖优先来源选择的 SOURCE_CAP 而被丢弃；不在本轮切换策略或提高名额。

## 逐题回归

原有 20 题全部保留，新增精确编号、中部步骤、边界邻近续发问法，共 23 题；原问题不改写。指定范围依原 Case.scope 显式传入，不把参考答案文档当成用户范围。第 11 题没有目标证据，各率为 null，不计作成功或失败，也不声称已验证模型拒答。

表内指标按“**A → B**”列出；排名顺序与 JSON targets 一致，✓ 表示完整目标标记在最终来源正文中保留，✗ 表示未保留。

| 问题 | Recall@8 | Recall@20 | Context Retention | Document Coverage | A 排名/保留 | B 排名/保留 |
| --- | --- | --- | --- | --- | --- | --- |
| 上线与回退条件 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 2 ✓ / 1 ✓ | 2 ✓ / 1 ✓ |
| 容量权限性能约束 | 1 → 1 | 1 → 1 | 0.6667 → 0.6667 | 1 → 1 | 8 ✗ / 5 ✓ / 6 ✓ | 8 ✗ / 5 ✓ / 6 ✓ |
| 相同审批条款来源身份 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 2 ✓ / 1 ✓ | 1 ✓ / 2 ✓ |
| 相互矛盾的版本说明 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 2 ✓ / 1 ✓ | 2 ✓ / 1 ✓ |
| 深排位证据 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 同文档互补事实 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ / 5 ✓ | 1 ✓ / 5 ✓ |
| 长分块尾部条件 | 0 → 0 | 0 → 1 | 0 → 0 | 0 → 0 | 23 ✗ | 17 ✗ |
| 中文同义与口语描述 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 指定文档范围检索 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 全项目范围检索 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 不存在的事实 | 未定义 → 未定义 | 未定义 → 未定义 | 未定义 → 未定义 | 未定义 → 未定义 | — | — |
| 高相似无关片段 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 多轮追问沿用来源 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 2 ✓ / 1 ✓ | 1 ✓ / 2 ✓ |
| 大工具结果预算 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 多源综合 | 1 → 1 | 1 → 1 | 0.6667 → 0.6667 | 1 → 1 | 6 ✗ / 1 ✓ / 3 ✓ | 7 ✗ / 1 ✓ / 4 ✓ |
| 指定-回滚触发条件 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 指定-只读下载权限 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 指定-性能指标 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 指定-运行期预算额度 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 指定-合同审批主体 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 精确编号 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 长文中部步骤 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 1 ✓ | 1 ✓ |
| 边界附近续发事实 | 1 → 1 | 1 → 1 | 1 → 1 | 1 → 1 | 2 ✓ | 2 ✓ |

最终实验 JSON 的 strictTailGatePassed=false，regressions=[]。所有目标逐项相似度、UTF-16 坐标和丢弃原因见 JSON，不用均值掩盖问题。针对重复背景“中部步骤”的新增题不声称证明独特中部条款；另行复跑既有 RealChunkingRetrievalComparisonPostgresTest，覆盖独特中部“四十八小时”条款（[补充证据](supplementary-middle-clause.json)：A/B 都是 Rank 1、相似度 0.7095、完整保留），保留其固定五方案回归但不据此再次搜索或选择参数。本轮生产决策只比较 1200/150 与 800/120，未进行 700/100 补充调参。

| 问题 | 检索耗时 ms（A → B） | 上下文 code points（A → B） | A → B 各证据相似度 |
| --- | ---: | ---: | --- |
| 上线与回退条件 | 5 → 11 | 808 → 923 | 0.629389 / 0.640883 → 0.629389 / 0.640883 |
| 容量权限性能约束 | 5 → 10 | 830 → 830 | 0.450312 / 0.479983 / 0.470709 → 0.450312 / 0.479983 / 0.470709 |
| 相同审批条款来源身份 | 5 → 9 | 1278 → 1281 | 0.805937 / 0.805937 → 0.805937 / 0.805937 |
| 相互矛盾的版本说明 | 5 → 10 | 821 → 821 | 0.806087 / 0.808131 → 0.806087 / 0.808131 |
| 深排位证据 | 7 → 10 | 862 → 862 | 0.882210 → 0.882210 |
| 同文档互补事实 | 6 → 9 | 1314 → 805 | 0.893391 / 0.621379 → 0.893391 / 0.621379 |
| 长分块尾部条件 | 7 → 12 | 798 → 798 | 0.364258 → 0.416180 |
| 中文同义与口语描述 | 6 → 11 | 785 → 785 | 0.885426 → 0.885426 |
| 指定文档范围检索 | 5 → 9 | 366 → 366 | 0.880059 → 0.880059 |
| 全项目范围检索 | 7 → 10 | 1713 → 1546 | 0.897454 → 0.897454 |
| 不存在的事实 | 10 → 13 | 1723 → 1555 | — → — |
| 高相似无关片段 | 12 → 7 | 1915 → 1519 | 0.781946 → 0.781946 |
| 多轮追问沿用来源 | 13 → 18 | 1291 → 1294 | 0.715522 / 0.715522 → 0.715522 / 0.715522 |
| 大工具结果预算 | 9 → 14 | 1711 → 1546 | 0.922778 → 0.922778 |
| 多源综合 | 9 → 10 | 1563 → 1657 | 0.552113 / 0.731698 / 0.629920 → 0.552113 / 0.731698 / 0.629920 |
| 指定-回滚触发条件 | 5 → 7 | 1049 → 1200 | 0.738138 → 0.738138 |
| 指定-只读下载权限 | 5 → 8 | 125 → 125 | 0.785689 → 0.785689 |
| 指定-性能指标 | 6 → 8 | 190 → 190 | 0.823445 → 0.823445 |
| 指定-运行期预算额度 | 7 → 6 | 133 → 133 | 0.817664 → 0.817664 |
| 指定-合同审批主体 | 6 → 6 | 51 → 51 | 0.784950 → 0.784950 |
| 精确编号 | 5 → 19 | 1930 → 1547 | 0.694082 → 0.694082 |
| 长文中部步骤 | 8 → 6 | 2426 → 2353 | 0.830532 → 0.820092 |
| 边界附近续发事实 | 8 → 8 | 1564 → 904 | 0.676337 → 0.726795 |

耗时为预热查询向量后的服务/SQL检索单次实测（不含真实模型网络延时），不构成性能提升结论；冷启动和执行顺序会影响毫秒值。

| 成本 | 历史预制参考 | A 1200/150 | B 800/120 |
| --- | ---: | ---: | ---: |
| 分块/未缓存时单文本实验调用数 | 38 | 47 | 53 |
| Embedding 输入 code points（含 overlap） | 12018 | 13368 | 13818 |
| 按历史→A→B执行，实际新增缓存未命中文本请求 | 37 | 3 | 5 |

查询向量每次实验预热 23 次，维度探针 1 次，跨方案共享正文缓存。实际增量调用数受缓存顺序影响，不能当成生产成本对照；无缓存调用数是本实验每次请求一段文本的口径，生产批量调用数量不能直接照搬。A/B 文本量变化 **3.37%**。来源身份断言同时校验 documentId/chunkId/正文前缀/metadata，范围题检查每项命中都在 scope 内，分块坐标回切原文且 hash 对应真实正文；上下文预算逐题断言。

## 索引与历史引用兼容性

以下区分静态代码审查和真实测试；本轮未改生产参数，也未实施真实用户数据重建。

- **旧 READY 文档**：检索不调用分块器，继续读取已存 chunk。SQL 只检查 READY、项目、Provider/model/dimension/fingerprint/generation；指纹没有 TARGET/OVERLAP，参数变化不会自动切换代际或重新索引。旧数据无需改写；不重建也不会自动获得新质量。真实 Postgres 的硬门槛覆盖指纹/代际双向隔离，历史预制参考证明旧格式仍可检索。
- **新上传**：DocumentProcessingService 调用当前生产 DocumentChunker，再将相同分块传给正文快照和 Embedding，最后由 DocumentIndexWriter 写入。当前仍是 1200/150。A 组真实向量入库和检索验证当前分块，B 仅在隔离实验写入；不能把 B 实验称为新生产上传验收。
- **明确重新索引**：resetForReindex 将 READY 改 UPLOADED、version+1；后续处理按当时分块器重建。replaceChunks 删除旧 document_chunk 后插入新 UUID，并不保留同号旧 chunk 身份。DocumentIndexWriter 在事务内校验处理租约与当前 Embedding 指纹/代际，再替换并标 READY。失败记录 FAILED；可显式重试。新正文快照可能已保存，旧向量不会因失败自动恢复成可搜索 READY；不承诺无中断回滚。相关恢复和正文读取行为由既有集成测试覆盖。
- **历史引用风险（已确认的代码事实）**：V1 中 knowledge_citation.chunk_id 外键为 ON DELETE CASCADE，替换旧 chunk 可删除历史知识问答引用行。Agent JSON 引用可能保留旧 ID，但不保证还能定位正文。DocumentContentService 对失效 chunk/snapshot 明确拒绝，不能用同号新块冒充旧来源。DocumentRepositoryIntegrationTest 验证旧 snapshot/chunk 拒绝与失败后正文可读；本轮不宣称已用新测试逐一演练所有历史引用级联情形。
- 因此“无需全量重建”不等于“重建无引用影响”，也不等于“历史文档自动提升”。若以后批准参数上线，必须评估具体重建范围及引用保全方案；本轮不扩展机制、不改 Flyway。

## 本地验证与 CI

全量命令实际结果：**BUILD FAILURE**，1626 tests / 0 failures / 1 error / 21 skipped（1604 通过）。唯一错误为 AgentRepositoryIntegrationTest.proposalRunCanReturnSuccessWhileApprovalRemainsPending 的 clearAgentFixtures 阶段：CannotGetJdbcConnectionException → PSQLException → Windows BindException: Address already in use: getsockopt。失败发生在测试建连准备阶段，与本轮 RAG 测试代码无调用关系；隔离复跑结果在下方补记。不为此扩大生产代码修改范围，也不把全量 verify 记为 passed。

最终全量中的 RAG/相关回归实际通过：AgentRetrievalQualityPostgresTest 4/4、RealChunkingRetrievalComparisonPostgresTest 2/2、RetrievalCoveragePostgresTest 16/16、RetrievalQualityHardGatePostgresTest 7/7、RetrievalCoverageTest 19/19、KnowledgeContextBuilderTest 18/18、CrossDocumentSourceSelectorTest 11/11、策略实验 1/1、长文实验 3/3、DocumentSourceOffsetTest 2/2、DocumentRepositoryIntegrationTest 1/1。Testcontainers 确实启动，RAG 容器组没有跳过；未削弱门槛。完整类级计数与失败原因保存在 [local-test-summary.json](local-test-summary.json)。

隔离复跑：**BUILD SUCCESS**，46 tests / 0 failures / 0 errors / 6 skipped，AgentRepositoryIntegrationTest **40/40 通过**。本次未开启 AI_REAL_OLLAMA_RETRIEVAL，两类真实 Ollama 测试合计 6 项全部跳过，验证默认 opt-in 边界。该结果支持前次连接错误属于瞬时环境故障；全量那一次仍保留失败状态，不改写为全量通过。未修改失败类或相关生产代码。

执行命令（位于 ai-collab-backend）：

1. AI_REAL_OLLAMA_RETRIEVAL=true + mvnw.cmd -B -ntp -Dtest=AgentRetrievalQualityPostgresTest test：首次定向 4 项通过。
2. 同一 opt-in + -Dtest=AgentRetrievalQualityPostgresTest#pairedProductionChunkingOnHistoricalCorpus,RealChunkingRetrievalComparisonPostgresTest test：3 项通过。随后补充丢弃诊断/准入判定字段，最终版在完整 verify 再执行。
3. AI_REAL_OLLAMA_RETRIEVAL=true + mvnw.cmd -B -ntp verify：最终全量执行，外部 Chat/Zen 和其他主机验收开关显式关闭。
4. 前端 pnpm lint、pnpm typecheck、pnpm test、pnpm build：均通过；49 文件 / 373 测试通过。lint 77 条既有 warning，构建有体积及第三方注释 warning，均非失败。前端源码无修改，无 UI 或浏览器操作需求。
5. git diff --check 与最终 diff 审查；普通提交并推送 dev，不等待 CI 长轮询。

仅调用 127.0.0.1 Ollama，未下载模型、未读取个人 API Key、未调用外部 Chat/付费 Provider、未修改真实项目配置或索引。真实模型默认仍由环境变量门控；CI 未启用时必须跳过。

最终 SHA 的 CI Run/attempt/status 在推送后通过 GitHub Actions 读取一次，记录在交付回执并随最终回复交付；pending 或 skipped 不写作 passed。若红灯则检查日志后记录归因。临时命令日志只放 target，交付前清理本任务日志；保留可复核 JSON 和测试摘要。

## 最终判断与证据边界

**NO_CHANGE_EVIDENCE_INSUFFICIENT**。已可靠重现生产基准 A 的原问题漏召回，也证实 B 增强数据库候选召回；但 **最终目标证据保留仍为 0**，严格联合门槛未通过，生产常量不变。不将第 17 名替代“真正可用的证据”，不放宽 TopK、来源上限、相似度或现有质量硬门槛。未观察到关键旧题不可接受的新退化，逐题结果全部公开；此结论只覆盖这些小型合成语料和本地模型，未验证真实 Chat 生成质量。完成本次取证后停止，不自动开展其他优化。
