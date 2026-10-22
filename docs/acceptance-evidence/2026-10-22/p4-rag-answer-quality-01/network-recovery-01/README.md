# 网络恢复后的续跑记录

2026-10-22 23:06（Asia/Shanghai）。**BLOCKED_CREDENTIAL_OR_PROVIDER**。

已按用户要求先做一次真实最小 generateContent 请求，输入为“请只回答 OK”，
仍收到 HTTP 503。遵守“最小请求失败即停止”的条件，没有重试，没有调用 Q01 或后续批量场景。
这次失败不构成 Gemini 答案质量缺陷，也不构成任何问答质量通过证据。

## 恢复基线与安全边界

- 起始 dev、本地 origin/dev 与远端 dev 均为 `58e67bf04b184ec2021694c21b63d7118b26c073`，工作区干净。
- main 与远端 main 保持 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`；不合并、不部署。
- 用户在本次消息明确恢复原授权：已用 4 次、累计最多 24 次，本次包括连通性最多新增 20 次。
  [原始调用台账](../call-ledger.json)可证明此前次数，无需重新请求授权。
- 当前进程没有预置 GEMINI_API_KEY，沿用上轮明确授权的同一桌面凭据文件，只传入 Maven 子进程环境，
  没有读取其他位置的凭据，没有打印、复制或写入 Key；本次未收到 401/403，不能据 503 判断新的认证结论。
- 不生成任何新语料。原 16 题、14 篇合成文档、人工评分标准和知识库测试入口全部复用；
  14 个文件的 SHA-256 与原 manifest 一致，问题、评分规则、原始台账的哈希保存在 [续跑台账](call-ledger.json)。
- Docker 引擎 29.8.0 可用，本地 Ollama 仍列出 qwen3-embedding:0.6b。
  本次在最小连通性就被阻断，没有启动业务 Testcontainers、执行 Embedding 或接触真实项目数据。
- 生产代码、知识库上下文构建、引用校验、Prompt、SOURCE_CAP、DocumentChunker 与数据库迁移均未修改。
  测试侧只在已有 GeminiLiveAcceptanceTest 增加精确输入、单次调用及证据记录的最小入口。

## Java 代理与真实请求

系统当前代理为 `127.0.0.1:7897`，ProxyEnable=1，7897 端口正在监听。
本次 Maven 测试进程显式使用 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`，
没有修改系统代理、全局 Java 配置或切换网络节点。

Java 默认 ProxySelector 对官方 HTTPS 端点实际返回：

```text
[HTTP @ 127.0.0.1/<unresolved>:7897]
```

源码核查：GeminiModelAdapter → JsonHttpModelClient，HttpClient.newBuilder 没有设置 NO_PROXY；
sendBounded 使用这个 client.send，出站策略仅校验公开 HTTPS，没有在此路径强制直连。
因此没有证据支持“本次没有走 7897”的解释。503 的更深层来源或服务不可用原因尚未确认，
不能仅凭 HTTP 状态断言用户节点、Key、模型质量或官方负载的具体问题。

请求通过真实 GeminiModelAdapter 发往官方
`https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent`，
没有 Mock、没有更换模型或账号。实际记录见 [connectivity.json](connectivity.json)。

| 项目 | 本次结果 |
| --- | --- |
| 输入 | 请只回答 OK |
| 请求模型 ID | gemini-3.1-flash-lite |
| 单次输出上限 | 2048 tokens |
| 新增生成尝试 | 1（累计第 5 次） |
| 新增完成数 / 服务失败数 | 0 / 1 |
| INCONCLUSIVE | 1；与上述失败为同一请求，不能相加 |
| HTTP 状态 / 应用错误 | 503 / AI_PROVIDER_ERROR |
| 观察时间 | 2026-10-22T23:06:12.357+08:00 |
| 本次自动或手动重试 | 0 |
| 最新累计 / 上限 | 5 / 24，剩余 19 次未使用 |
| 累计故障 | 1 次连接超时 + 4 次 HTTP 503 |
| 实际响应模型版本、输入/输出 token、费用 | 无成功响应，不可得；不记为实测 0 |

Provider 故障以 INCONCLUSIVE 保存，JUnit 采用 abort/skip；Maven 的 BUILD SUCCESS 只说明
没有违反测试运行机制，**不表示这次真实生成请求成功**。

## 回答评分与业务链路状态

[assessment.json](assessment.json)逐题列出 Q01–Q16、Q03/Q07 的 SSE 与 Agent 场景状态。
这些场景本次全部为 BLOCKED，未发起对应 Gemini 调用；没有生成回答、引用或持久化结果可以评分。
Q01 本次没有进入检索，因此不能借用上轮上下文记录冒充本次成功验证。

Fact Correctness、Required Facts Coverage、Faithfulness、Citation Validity、Citation Support、
Conflict Handling、Abstention、Scope Compliance、Safety、Persistence 均为 N/A；
关键失败数未可评估，分母为 0 个完成答案。未用模拟答案或 Provider 错误充当模型拒答。

主因仅为 **MODEL_PROVIDER_INCONCLUSIVE**。
没有本次证据支持 RETRIEVAL_MISS、CONTEXT_MISS、GENERATION_FAILURE、
CITATION_VALIDATION_FAILURE 或 SSE_OR_PERSISTENCE_FAILURE，后续链路均未执行。
本次不提出或实施生产 RAG 算法修改。

## 本地回归与交付

精确计数及命令见 [local-test-summary.json](local-test-summary.json)。

- 显式 `AI_GEMINI_LIVE_TESTS=true`、`AI_GEMINI_ACCEPTANCE_RECOVERY=network-recovery-01`，
  仅选择 `GeminiLiveAcceptanceTest#networkRecovery_minimalOkWithoutRetry`：
  1 test / 0 failures / 0 errors / 1 skipped（503 导致 INCONCLUSIVE），真实尝试 1 次。
- 关闭真实调用开关后执行原有安全门控、Gemini turn/stream 协议、模型路由、知识库上下文与引用、
  冻结语料及新入口定向回归：85 tests / 84 passed / 0 failures / 0 errors / 1 skipped。
  其中 skipped 是本次新 live 入口，不能计作真实 Provider 成功。
- 新入口保留已有严格开关与精确目标检查，未放宽 GeminiLiveTestSupport；无 Key 时不访问 Provider。
  本次没有修改生产实现，未重复上一轮的全量 verify 或前端长流程；上轮全量结果仍仅代表上轮 SHA。
- 所有临时日志只写 backend/target，交付前删除本轮临时 *.log；凭据内容比对扫描、
  原始四次证据/冻结语料不变检查、Markdown 链接与 git diff --check 均在提交前完成。
- 普通提交并推送 dev，工作区保持干净；最终 SHA 与一次读取的 CI Run / attempt / SHA / status
  保存到[本地交付回执](../../../../../ai-collab-backend/target/rag-answer-quality-01/network-recovery-01/delivery-receipt.json)，并在聊天给出。
  不等待 CI 变绿，不将 queued/pending 当作 success。
