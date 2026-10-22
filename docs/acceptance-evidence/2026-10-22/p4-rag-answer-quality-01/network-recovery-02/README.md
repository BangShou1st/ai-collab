# 再次有界重试与 Google 错误详情

2026-10-22，Asia/Shanghai。**BLOCKED_CREDENTIAL_OR_PROVIDER**。

用户在前次单次续跑仍失败后要求“多试几次”。本次在累计 24 次上限内追加最多三次真实请求，
仍使用 gemini-3.1-flash-lite、同一授权凭据来源、7897 代理和 2048 输出上限。
未切换截图中的其他模型，没有改变系统代理或节点。

三次均返回 HTTP 503；新增的脱敏诊断取得了 Google 的具体错误：

```json
{
  "code": "503",
  "status": "UNAVAILABLE",
  "message": "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later."
}
```

因此本次证据指向接口报告的模型高需求/临时不可用。用户截图中的 RPM/TPM/RPD 未到上限，
不能排除这种服务可用性问题；本次返回的并非 429 配额错误。没有证据据此归咎用户额度、
Key 无效或生产 RAG 实现。具体基础设施负载没有另行验证。

## 实际尝试与预算

| 累计请求序号 | 开始时间（北京时间） | 结果 |
| --- | --- | --- |
| 6 | 23:17:35 | 503 UNAVAILABLE，高需求 |
| 7 | 23:17:45 | 同上 |
| 8 | 23:17:52 | 同上 |

- 新增 3 次生成尝试，成功 0，服务失败/INCONCLUSIVE 3；后两次为重试，间隔 3 秒与 6 秒。
- 累计 8/24 次，剩余 16 次未消耗。累计为 1 次连接超时、7 次 HTTP 503。
- 三次都经 Java 记录的 `[HTTP @ 127.0.0.1/<unresolved>:7897]`；响应协议 HTTP/2，
  Server 为 `scaffolding on HTTPServer2`，Content-Type 为 `application/json; charset=UTF-8`。
- 请求模型 ID 是 gemini-3.1-flash-lite；没有成功 completion，实际响应模型版本、token 用量、费用不可得。
- 达到本组三次上限后停止。没有执行 Q01–Q16、SSE 或 Agent，没有模型回答或引用可评分。

完整逐次证据见 [connectivity.json](connectivity.json)，累计台账见 [call-ledger.json](call-ledger.json)。
[前次单次续跑](../network-recovery-01/README.md)和原始四次失败证据原样保留。
冻结问题、语料和评分基准未重新生成，未修改生产代码。

## 诊断方法与边界

复用 GeminiLiveAcceptanceTest、严格 GeminiLiveTestSupport 门控、真实 GeminiModelAdapter
和 JsonHttpModelClient。测试中只为实际 JDK HttpClient 增加委托包装，原样发送请求并返回真实响应，
没有 Mock、伪造响应或重写生产 Prompt/请求协议。

包装仅记录 HTTP 状态、协议、Server/Content-Type/Retry-After/Via 白名单响应头及 error.code/status/message；
错误消息限长并替换凭据原文，不保存请求认证头或完整错误响应体。这样保留生产错误映射行为，
同时能确认此前被生产通用错误分类隐藏的具体原因。

原始 16 题的十维质量评分仍全部 N/A，完成答案分母为 0；主因仍为 MODEL_PROVIDER_INCONCLUSIVE。
不将供应商失败写为 GENERATION_FAILURE，不把 Maven 机械成功或 JUnit skip 当作真实回答通过。

## 本地验证与 Git

起始 dev/origin/dev 为 `f2f8cfc6901115a5eb9fd9bf99f4c126b5abfc83`，工作区干净。
main 保持 `9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7`。

- 真实入口：仅显式选择 `GeminiLiveAcceptanceTest#networkRecovery_boundedRetriesWithResponseDiagnostics`，
  设置 AI_GEMINI_LIVE_TESTS=true、AI_GEMINI_ACCEPTANCE_RETRY=network-recovery-02，
  Maven 进程指定 https.proxyHost=127.0.0.1、https.proxyPort=7897。
  1 test / 0 failures/errors / 1 skipped（真实 Provider INCONCLUSIVE），实际调用三次。
- 关闭真实开关后的安全门控、Gemini 协议、路由、知识库上下文/引用、语料以及新入口定向回归，
  结果见 [local-test-summary.json](local-test-summary.json)。本次不重复前端或完整 verify，未改生产代码。
- 提交前核查冻结输入哈希、凭据内容比对、Markdown 链接及 git diff --check，清理本轮 target 临时日志。
- 普通提交推送 dev；最终 SHA 与一次读取的 CI Run/attempt/SHA/status 记录在
  [本地交付回执](../../../../../ai-collab-backend/target/rag-answer-quality-01/network-recovery-02/delivery-receipt.json)，
  并在聊天给出；不等待 CI 变绿。
