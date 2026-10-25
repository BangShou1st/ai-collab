# 现有能力、缺口与执行设计

研究依据为当前 dev 源码，以及 P6 设计、报告、研究与 P4/P6/P7 验收目录。历史结果仅解释选题，不计入本轮通过数。

| 测试入口 | 实际边界 | 门控/成本/输出 | 本轮用途 |
| --- | --- | --- | --- |
| P6RagQualityBaselinePostgresTest | 生产分块、真实 Ollama、生产搜索 SQL、真实 pgvector、QA/Agent 上下文；没有 Agent Runtime | AI_REAL_OLLAMA_RETRIEVAL；14 篇冻结文件、16 题；原输出 target/retrieval-baseline/p6-rag-quality-01/d0-d2-real.json | 冻结题集主基线；增加可指定输出路径、SHA 和测试扩展点 |
| AgentRetrievalQualityPostgresTest | 真实本地 Embedding、生产搜索、20 个主样本（14 全项目 + 6 指定范围；另6反事实对照）与选择器反例；其他方法还包括分块对照 | 相同本地门控；全部 4 方法执行，输出 target/retrieval-baseline/p4-rag-quality-* | 沿用真实语义质量门槛，另列分母 |
| GeminiKnowledgeAnswerQualityPostgresTest | 真实 QA 生成和引用持久化，需 Gemini 凭据及 opt-in | AI_GEMINI_LIVE_TESTS + 显式类名选择；历史授权 24/24；target/rag-answer-quality-01 | BLOCKED_EXTERNAL，无本轮新授权；不读取凭据 |
| AgentResearchQualityPostgresTest | 实际 Runtime、注册器、执行器、正文工具、临时 PostgreSQL、父子证据回收；搜索候选和模型脚本注入，权限 guard 为 Mock | 非外部门控；target/retrieval-baseline/2026-10-19/agent-research-quality-paired.json | 工具失败、父子协作与确定性回归；不能证明语义检索 |
| RealAgentLoopPostgresTest | 实际外部 Kilo Chat、Runtime、工具和数据库；搜索为关键词 Mock，guard 为 Mock | AI_REAL_AGENT_LOOP；每场景 12 次模型/180 秒上限；target/retrieval-baseline/2026-10-19/real-agent-loop.json | BLOCKED_EXTERNAL；历史外部成功不证明当前真实向量跨层链路 |
| KnowledgeContextBuilderTest / CrossDocumentSourceSelectorTest | 确定性来源选择、去重与预算 | 无外部依赖 | 单元回归 |
| RetrievalCoverageTest | 测量计算口径 | 无外部依赖 | 复用事实、覆盖与召回分层口径 |
| RetrievalQualityHardGatePostgresTest | 真实 pgvector 与生产搜索，Embedding 为 SyntheticEmbedder/Mock | 无外部门控；7 条不变量 | 身份、scope、fingerprint/generation、预算、去重硬门槛 |
| AgentResearchCitationMetricsTest | 引用计分器的未知/畸形身份负例 | 无外部依赖 | 防止丢弃错误引用后虚增通过率 |

当前缺口是：冻结真实语义候选没有通过上述脚本 Agent 测试的完整执行路径。P8 复用 P6 的同一临时数据库与索引，在每题测量后、Q13 失效前执行生产搜索工具，经真实 Runtime、工具调度/清洗与消息组装回到脚本模型，再核对数据库。Q11/Q12 使用同一 session；Q12 显式继承上一轮用户范围，不验证自主问题改写。P8 实际使用数据库 guard 检查成员权限。

执行顺序：只读基线及旧输出保护 → 真实检索/既有门槛 → P8 确定性跨层 → 相关回归 → 完整后端 verify → 证据与未知项汇总。脚本响应只复制本轮实际观察到的 quote；不以金标准补齐答案，不为掩盖 Q02 调整阈值或候选。复用的 R1/S1/limit12 都是测试侧诊断对照；不进入生产。

生产 Java、Prompt、模型配置、迁移及前端均不修改。HTTP/UI 浏览器验收不在本轮范围；健康探针仅检查 HTTP 状态，没有发起业务生成。外部真实生成、真实模型抗注入与自主续轮推理均不执行。
