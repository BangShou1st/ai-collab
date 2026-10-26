# P10-REAL-GENERATION-02 研究：出站选路修复后的真实生成前置条件

## 结论与研究边界

本轮是**阶段一只读核验与预检**，真实 Chat、外部 Embedding、Agent 与模型裁判调用均为 **0**。
结论只回答"真实生成的前置条件是否成立"，不回答"模型回答质量如何"——后者需要授权后的真实回答才能评价。

三个核心判断：

1. **01 批次的失败原因不在模型路由。** 本轮用生产代码路径（同 SQL、同字段序、同 Jackson 序列化、同 SHA-256）
   复算出的路由哈希 `df17eb978e42ecb533a8f1f7a6ff756c6e1521abe6308f9671dca06e9123cbbc` 与 01 批次
   账本记录**完全一致**，说明路由配置（Provider、模型、端点、参数、能力）自 2026-10-23 起未变。
   因此 01 批次的 20,912 ms 超时不能归因于"配错了模型"，只能归因于当时出站选路为 DIRECT。
2. **网络前置现在成立，且比 01 批次的证据更强。** 01 批次只做过"显式经 7897 的 CONNECT/TLS"诊断，
   而实际执行 JVM 仍走 DIRECT；本轮在**实际 Surefire fork** 中复测，选路 PROXY、CONNECT 200、
   TLSv1.3、主机名校验通过。此外本轮用带凭据的**只读** `GET /v1beta/models` 经同一代理返回
   HTTP 200——这比 01 批次"裸 CONNECT 成功"多证明了一层：**代理隧道 + TLS + 凭据鉴权 + Provider
   应用层可达**都成立。但它仍然不是一次 Chat 请求，**不证明 Provider 会接收推理请求**。
3. **模型仍受该 Provider 支持。** 只读 `GET /v1beta/models` 返回 200 与 50 个模型，其中包含
   `gemini-3.5-flash-lite`；`GET /v1beta/models/gemini-3.5-flash-lite` 返回 200，
   `version=3.5-flash-lite-07-2026`，`supportedGenerationMethods` 含 `generateContent`，
   `outputTokenLimit=65536` 远高于本轮 1024 的封顶。
   这类发现调用不消耗推理配额，因此可以用它回答"模型是否还在"而不违反"不得用真实 Chat 测额度"的约束。

## 重试、切换与封顶的代码事实

| 关注点 | 事实 | 依据 |
| --- | --- | --- |
| 自动重试 | **无**。Gemini 非流式走 `http.post(url, headers, body)` → `maxAttempts=1`；`post` 的重试分支只在 `maxAttempts>1` 时进入 | `JsonHttpModelClient` |
| 模型切换 | **无**。`RoutingChatModelGateway` 只解析一次 provider 后直接调用适配器；失败向上抛出 | `RoutingChatModelGateway` |
| 路由回退 | 存在"用途分配 → 默认模型"的回退，但**不是失败后的切换**；本轮两者都指向同一个 Gemini 配置 | `UserAiProviderService.resolve` |
| 单次输出上限 | `AiRequestOutputCap(1024)` 在请求线程内生效，`effective()` 取 `min(持久配置 2048, 1024)`，写入出站 body 的 `generationConfig.maxOutputTokens`；不修改数据库配置 | `AiRequestOutputCap` / `GeminiModelAdapter` |
| 传输超时 | 请求 10 分钟、连接 15 秒；`AiRequestDeadline` 未设置时取传输上限 | `JsonHttpModelClient` / `AiRequestDeadline` |
| 错误映射 | 401→`AI_MODEL_CREDENTIAL_INVALID`、403→`AI_PROVIDER_REQUEST_REJECTED`（含 `FreeTierError` 识别）、404→`AI_PROVIDER_NOT_FOUND`、429→`AI_PROVIDER_QUOTA_EXCEEDED`、408/504→`AI_MODEL_TIMEOUT`、其他 5xx→`AI_PROVIDER_ERROR` | `JsonHttpModelClient.checkStatus` |
| 引用校验 | `[S#]` 编号合法即保留、非法成员移除、**无有效引用的实质回答整体替换为统一拒答文案**（`invalidOutput`） | `KnowledgeCitationValidator` |
| 频率限制 | 应用侧 `knowledge.rate-limit-per-user-hour` 默认 60 次/小时/用户，本轮 8 次远低于该上限 | `KnowledgeRateLimiter` / `application.yml` |

**一个必须保留的区分**：`AiOutboundObservation.markStarted` 位于 `client.send` 之前，
所以 `transportStarted=true` 只证明"真实客户端传输尝试发生"，**不证明**请求体离开本机、
Provider 已接收、已生成或已计费。01 批次的账本已经用 `providerAcceptance=UNKNOWN` 如实表达了这一点，
本批次沿用同一口径。

## 零 Chat 预检的实际结果

在隔离的 Testcontainers `pgvector/pgvector:pg17` 上重跑本地预检（`P10_LOCAL_PREFLIGHT=true`，
`AI_REAL_CHAT_ANSWER_QUALITY` 未设）：

- 8 题全部到达出站边界并返回合成传输响应，状态 `PREFLIGHT_SYNTHETIC_TRANSPORT`；
- `chatDispatchReservations=0`、`syntheticTransportResponses=8`、`externalEmbedding=0`、
  `agent=0`、`judge=0`、`chatRetries=0`；
- 每题实际检索候选 8 / 入选来源 5，出站 body `maxOutputTokens=1024`，
  来源 documentId/chunkId 在当次一次性数据库核验通过；
- 合成响应是固定的"当前项目资料不足以回答该问题"，**不参与任何质量评分**。

这证明生产检索 → 上下文装配 → 出站边界 → 引用校验 → 持久化这条链路在当前代码上仍然通畅，
且外部出站为 0。它**不证明**真实模型会返回内容。

## 批次隔离与旧证据保护

01 批次已关闭。本轮不重跑、不覆盖、不复用其标识：

- 门禁 `P10RealGenerationGate` 显式拒绝 `p10-real-generation-01`，要求
  `P10_REAL_BATCH_ID` 与大写 `P10_APPROVAL` 相互匹配；
- 运行输出目录按批次分离：`target/p10-real-generation-02/real/`；
- 新增的 `build-real-evidence.cjs` 与 01 批次同名脚本**完全独立**：只读本批次 target 输出、
  只写本批次目录、不引用 01 批次任何文件路径或任务 ID；
- 复核：01 批次 manifest 26/26 OK、0 失败；`raw/real/` 与 `target/.../real/` 逐字节一致；
  `target/p10-real-generation-01/real/model-call-ledger.json` 仍在且 `task=P10-REAL-GENERATION-01`。
- **没有删除旧 ledger、没有清空旧输出、没有绕过"已有真实 ledger 拒绝重跑"保护。**

## 不可得与未核实的边界

| 项 | 状态 | 原因 |
| --- | --- | --- |
| 账号计费状态 | `UNAVAILABLE` | 本轮环境无 Web 访问，也无计费查询接口；用户此前自述免费 API，未独立核实 |
| 免费额度剩余 | `UNAVAILABLE` | 同上；本轮 8 次请求的实际占用无法预先确认 |
| 速率限制（RPM/RPD） | `UNAVAILABLE` | 官方页面在本环境不可读 |
| 实际费用 | `UNAVAILABLE` | 无计费回执；不按推算值记账 |
| Provider 是否接收推理请求 | `UNKNOWN`（执行后才知道） | 本轮零 Chat，无任何推理层证据 |

有一条**间接**可用性证据值得记录但不构成结论：本地 `ai_call_log` 中该模型有 5 次 `SUCCESS`
与 1 次 `INVALID_OUTPUT`（2026-10-23/24），说明该 API Key 此前可正常调用并返回内容。
它**不证明**当前剩余额度，也**不证明**本轮一定成功。

## 下一步

没有证据支持改生产 Prompt、检索算法、来源选择、上下文预算、模型路由或输出上限。
本轮唯一需要的动作是：**获得用户对本批次的明确授权后**，按既有 P10 门禁执行 8 题，
每题最多一次 Chat、间隔 ≥15 秒、失败即停，然后用冻结评分器与人工审查分别裁决执行结果与模型质量。

完整证据：[README](../../acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)、
[预检快照](../../acceptance-evidence/2026-10-26/p10-real-generation-02/preflight-summary.json)。
