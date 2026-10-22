# P4-RAG-ANSWER-QUALITY-01：真实 Gemini 回答质量验收

> 首次网络恢复续跑见 [network-recovery-01](network-recovery-01/README.md)，当时累计 5/24 次。随后按用户要求追加的 [network-recovery-02](network-recovery-02/README.md) 三次尝试均返回明确的模型高需求错误，最新累计 8/24 次。下文及原始 JSON 保留上轮 4 次尝试的历史记录。

**最终状态：BLOCKED_CREDENTIAL_OR_PROVIDER（Provider 阻断，凭据有效）。**

2026-10-22，Asia/Shanghai。官方模型列表成功返回；但真实知识库业务调用先连接超时，
配置本次 Java 进程使用现有代理后又连续三次返回 HTTP 503，没有取得任何可评分回答。
不能判定 Gemini 回答质量合格，也不能据此判定生成质量存在缺陷。已停止外部调用，未消耗剩余额度继续尝试。

## Git 与环境

- 起始 dev、本地 origin/dev 与远端 dev：`e36c14ff49213c7459901c6262a40a512df0e0fe`，起始工作区干净。
- main、本地 origin/main 与远端 main：`9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`，不修改、不合并。
- 最终 SHA 为承载本目录的 dev 提交；提交后在聊天明确列出精确 SHA，避免提交内自引用。
- 生产代码、前端、DocumentChunker、SOURCE_CAP、Prompt、Flyway、真实项目数据均未修改。
- 本地 Docker 引擎 29.8.0；真实一次性 Testcontainers `pgvector/pgvector:pg17`，成功迁移 V1–V65。
- 本地 Ollama `qwen3-embedding:0.6b`，实际返回 1024 维；指纹
  `20f29e8d94afc1eb2f5de49d3f4c81f548881de08e7aa4acb955ec87f5face75`。
- 请求模型 ID：`gemini-3.1-flash-lite`；已通过官方模型列表核实存在并支持 generateContent。
  因无生成成功响应，**没有可报告的实际 response.modelVersion**。
- 用户明确授权读取指定凭据、使用此模型、最多 24 次生成尝试、单次最多 2048 输出 tokens；
  环境变量 opt-in 与精确测试目标同时启用。凭据有效性通过官方列表请求验证，内容不保存到仓库。
- 只为检查已有配置启动了原来停止的默认开发 PostgreSQL，查询仅包含模型元数据和凭据存在布尔值；
  验收本身使用临时库。最终恢复该默认容器原先的停止状态。既有隔离实例不修改。

接入历史、用途选择和实现边界见 [source-audit.md](source-audit.md)。

## 实际调用与费用

权威逐次记录：[call-ledger.json](call-ledger.json)。

| 项目 | 实测 |
| --- | ---: |
| 官方模型列表 GET | 1 次成功（不计生成请求） |
| 生成尝试 / 授权上限 | 4 / 24 |
| 返回可评分回答 | 0 |
| INCONCLUSIVE 生成尝试 | 4（不是 4 个独立质量样本） |
| 连接超时 | 1 |
| 官方端点 HTTP 503 | 3 |
| 重试（包括更正进程代理后的重启） | 3 |
| 最后一个进程内自动 503 重试 | 1 |
| 每次输出上限 | 2048 tokens |
| Provider 返回 token 用量 | 不可得，不能记为实测 0 |
| 费用 | 用户说明为免费调用；无账单/用量响应，不能宣称已测得零费用 |

第一次超时计入预算，但不能证明 Google 收到了该请求；其后三次明确收到 HTTP 503。
三次 503 分别为一次代理修正后的尝试与两次有界续试，没有无限重试、换账号或换模型。
没有成功回答被重复调用，也没有自动开展第二轮质量实验。

早期记录：[transport-attempt-1.json](transport-attempt-1.json)、
[transport-attempt-2.json](transport-attempt-2.json)。最终业务证据：[business-results.json](business-results.json)。
最后一份旧 recorder 曾把 automaticRetries 写成 0；归档时根据 requestNumbers=[3,4]、
retryReasons=[HTTP_503] 和两份前序记录修正了统计元数据，保留 correction 说明；没有改写候选、Prompt 或回答。
测试源码已同步修正统计方式。

## 问题集、语料和业务边界

[questions.json](questions.json) 在首个生成请求前冻结 16 题与 34 个必答事实单元，
覆盖事实、长尾条件、多文档、互补分块、版本冲突、精确编号、无答案、高相似干扰、
相同条款不同来源、限定范围、多轮以及提示注入。复合事实单元须保留其中所有条件才算覆盖。
[rubric.md](rubric.md) 给出 10 维 0/1/2/N/A 评分、关键失败规则、引用范围和主次归因规则。

语料位于 [tests/fixtures/documents/p4-rag-answer-quality-01](../../../../tests/fixtures/documents/p4-rag-answer-quality-01/)。
13 篇原文从既有 CORPUS 完整重建，逐篇 SHA-256 与 P4-RAG-QUALITY-02 一致，
保留长附录、性能噪声、旧版本及全部章节；额外一篇明确标注的合成注入检测资料仅在 Q13 范围内启用。
生产分块仍为 1200/150，没有用实验参数或人工挑选来源补齐上下文。
逐篇字节哈希与换行边界记录见 [corpus-manifest.json](corpus-manifest.json)：实际尝试时的 Windows
文件为 CRLF，先前比对的是归一化文本哈希；归档统一为 LF 后，字节哈希也与历史一致。
离线生产分块器回归另核对 CRLF/LF 的标题、正文和内容哈希，源坐标随换行长度不同，不宣称坐标相等。

真正执行到的链路为：生产项目成员校验与会话归属 → 限流 → 真实 Ollama 向量
→ DocumentSearchService / Repository / Mapper → 临时 pgvector → 生产 build()
→ 用户用途路由 → GeminiModelAdapter → 官方端点。
生成失败后没有伪造 completion，没有绕过引用校验，也没有保存一条成功回答。
**这是业务服务真实调用尝试，不是完成的知识库 E2E 验收。**

Q01 的 Top8 中存在正确的“上线门禁”证据，最终 5 条来源的 S1 完整包含：
核心业务流程自动化验收、所有阻断级缺陷关闭、迁移结果核对无误。
完整来源 UUID、正文、相似度、候选顺序、丢弃原因、system/user Prompt 均在业务 JSON。
最终系统 Prompt SHA-256 为
`8e5db699ad355e136936828bbf7c97c05d19f53a292f0317f55023c77ac4f4e1`；
该值包含生产动态时间上下文，不是一个跨时间不变的 Prompt 版本号；静态实现版本以起始 Git SHA 标识。

## 逐题结果与评分

完整评估状态与证据指针：[assessment.json](assessment.json)。

| Question ID | 场景 | 结果 | 原因 |
| --- | --- | --- | --- |
| Q01 | 发布前条件 | INCONCLUSIVE | 正确证据已入上下文，Provider 超时/503，无答案 |
| Q02 | 长文续发前提 | BLOCKED | Provider 阻断后未执行 |
| Q03 | 上线/灰度/回滚综合 | BLOCKED | 同上 |
| Q04 | 导出失败与审计互补事实 | BLOCKED | 同上 |
| Q05 | 新旧版本冲突 | BLOCKED | 同上 |
| Q06 | REQ-7741 | BLOCKED | 同上 |
| Q07 | 审计八十年无答案 | BLOCKED | 同上 |
| Q08 | 性能指标与高相似噪声 | BLOCKED | 同上 |
| Q09 | 两份合同相同条款 | BLOCKED | 同上 |
| Q10 | 限定合同甲 | BLOCKED | 同上 |
| Q11 / Q12 | 预算及同会话追问 | BLOCKED | 同上，未形成多轮记录 |
| Q13 | 文档提示注入 | BLOCKED | 同上，不宣称防护通过 |
| Q14 | 容量与大小精确数字 | BLOCKED | 同上 |
| Q15 | 限定性能文档 | BLOCKED | 同上 |
| Q16 | 只读下载与脱敏 | BLOCKED | 同上 |
| Q03-SSE / Q07-SSE | 计划中的两个流式对照 | BLOCKED | 未发起流式 Gemini 请求 |
| Q03-AGENT / Q07-AGENT | 计划中的两个只读循环 | BLOCKED | 未运行真实 Gemini Agent-loop |

| 必报指标 | 结果 | 分母与限制 |
| --- | --- | --- |
| Fact Correctness | N/A | 0 个完成答案 / 16 个计划普通问题 |
| Required Facts Coverage | N/A | 34 个预标注单元，0 个答案可评分，不能写成 0% 或 100% |
| Faithfulness | N/A | 0 个完成答案 |
| Citation Validity | N/A | 没有模型引用，不能用空集宣布 100% |
| Citation Support | N/A | 没有可核查的结论与引用 |
| Abstention | N/A | 无答案问题尚未调用；Provider 错误不等于模型拒答 |
| Scope Compliance | N/A | 指定范围问题未执行 |
| Critical Failure Count | 未可评估 | 观察到 0 项，但评估答案数为 0，不能证明无关键失败 |

10 个评分维度全部未可评估，均用 null/N/A；不将其记作 0 分或 2 分。
普通题为 1 个 INCONCLUSIVE + 15 个 BLOCKED；另外 2 个流式、2 个 Agent 场景 BLOCKED。
没有语义评分来自 Gemini 自评，没有模拟输出冒充真实回答。

## 流式、Agent 与根因

流式测试设施会记录生产 token / citations / done / error 事件、最终校验文本与数据库内容，
但本轮未执行，因此不能判断文本、引用、持久化、终止事件是否一致。
设施是业务服务级 SseEmitter 捕获，不覆盖 HTTP 序列化、浏览器或断线续传。

Agent 测试设施使用生产 AgentRunService / AgentWorker / Runtime、用途绑定、租约与循环预算，
只向模型暴露真实 search_project_knowledge 只读工具；本轮真实执行数为 0。
不能比较普通问答与 Agent 的生成质量，也不能把预备代码编译成功写成 Agent 通过。

Q01 主因是 **MODEL_PROVIDER_INCONCLUSIVE**，首次另有本地 Java 代理未配置造成的连接超时。
正确证据在候选和上下文均完整存在，因此本题不能归因为 RETRIEVAL_MISS、CONTEXT_MISS
或 GENERATION_FAILURE。没有观察到引用校验失败、持久化失败或越权证据，但这些后续阶段未完成。
其余题为未执行，不补造失败归因。

后续只建议一项：Provider 恢复后，经用户重新授权继续使用此冻结问题集进行首轮质量测量。
当前没有真实生成证据支持修改生产 Prompt、分块、SOURCE_CAP 或引入 Reranker。

## 本地测试、CI 与交付

后端目录中执行：

1. `mvnw.cmd -B -ntp -Dtest=GeminiLiveTestSupportTest,GeminiTurnContractTest,GeminiStreamContractTest,KnowledgeContextBuilderTest,KnowledgeCitationValidatorTest,RoutingChatModelGatewayTest test`：82 passed，0 failures/errors/skipped。
2. 新测试初次编译发现类型转换问题；隔离装配又发现 JWT 必填属性、测试用户名长度问题；
   均已在测试侧修正，发生时 Gemini 生成调用数为 0。
3. 显式 opt-in、模型环境变量和精确 `#productionKnowledgeAnswersAndStreams` 目标执行真实业务尝试。
   首次超时记录的 harness 仅机械通过，不是质量通过；后续改为 Provider 不可用时 JUnit abort。
   最终 live 命令为 1 test / 0 failures / 0 errors / 1 skipped（INCONCLUSIVE），并保存全部证据。
4. 外部 live 开关全部关闭后执行 `mvnw.cmd -B -ntp verify`；精确最终结果见 [local-test-summary.json](local-test-summary.json)。
   **1631 tests / 1601 passed / 0 failures / 0 errors / 30 skipped，BUILD SUCCESS，13:33。**
   Testcontainers 确实执行：RetrievalQualityHardGatePostgresTest 7/7、AgentResearchQualityPostgresTest 7/7 等容器回归通过。
   新 live 类 2/2 跳过；其余跳过为既有环境门控，不把 live 跳过当通过。
   全量之后补跑 `GeminiAnswerQualityFixtureTest`：2 passed / 0 failures/errors/skipped，验证历史字节哈希与生产分块器 CRLF/LF 正文一致性，无外部调用。
5. 前端无修改，按本轮任务约定不重复前端长流程；未进行浏览器验收。
6. `git diff --check`、凭据内容比对扫描、UTF-8/语料 hashes、产物与改动范围检查后，普通提交和推送 dev。
   临时命令日志仅写 backend/target，交付前清理本轮临时 *.log。

推送后读取一次对应提交的 CI Run / attempt / SHA / 状态，写入[本地交付回执](../../../../ai-collab-backend/target/rag-answer-quality-01/delivery-receipt.json)并在聊天报告。
CI pending 不当作 success，不等待长轮询；本地回归和 Provider 质量结论分开报告。
