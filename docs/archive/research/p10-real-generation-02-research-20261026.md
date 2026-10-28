# P10-REAL-GENERATION-02 研究：出站选路修复后的真实生成前置条件与首次回答质量观测

## 结论与研究边界

本轮**分两阶段**：阶段一只读核验与预检（真实 Chat 0 次），阶段二获批后执行真实生成（8 次 Chat，
8 题全部取得回答）。外部 Embedding、Agent 与模型裁判调用全程为 **0**。

本报告覆盖两阶段。**先读阶段二结论，再回看阶段一的论证**：

> **阶段二核心结论**：8 题全部执行成功，机械评分器报出的 3 项高严重度经逐条复核**全部为评分器
> 字面匹配局限**（否定语境 + Markdown 强调未被识别），**无任何一题构成模型事实错误**。
> 逐题关键事实上下文可得性全部 `RETAINED`，**本轮没有任何一题可归因为检索层问题**。
> 唯一观察到的系统性缺口是 AQ01 的**版本生效/废止条款未进入所选块**——属检索/上下文层，
> **不是**生成错误。独立人工复核 0 题。

## 阶段二：真实回答观测（获批后执行）

### 调用与用量

8 次请求全部 `HTTP_RETURNED` 且带 `usageMetadata` → Provider 已接收并成功生成。
`promptTokenCount` 合计 6,749、`candidatesTokenCount` 合计 760、`totalTokenCount` 合计 7,509、
`serviceTier=standard`；延迟 820–2,644 ms。Provider **未**报告思考 Token。费用 `UNAVAILABLE`。

对比 01 批次（本轮为**首次**在该链路上取得回答）：01 批次 1 次传输尝试 20,912 ms 后超时、
无响应、无 Token。本轮 8/8 成功，**出站选路修复是有效的前提修复**——这证实了 01 批次的
"预检漏核 JVM 出站代理"确实是准备缺口，而不是模型或路由问题。

### 评分器局限是本轮唯一的"错误来源"

机械评分器报出的 3 项高严重度，逐条复核后**全部为假阳性**：

1. **AQ12 `FORBIDDEN_AFFIRMATION: 每年抽查`** —— 该短语出现在**否定**从句
   "资料中**并未明确**具体每年抽查多少份合同"中。评分器用 `answer.contains(marker)` 纯字面判断。
2. **AQ12 `ABSTENTION_MISSING`** —— 模型用"尚未确定/并未明确"表达不确定，
   但这两个短语不在**冻结**的 `abstentionPhrases` 列表内。冻结列表本轮未修改。
3. **AQ06 `FORBIDDEN_AFFIRMATION: 可以先组织培训再补签`**（由独立复核触发，评分器本身未报）——
   模型写作"通常**不可以**先组织培训再补签合同"，否定词被包在 Markdown 粗体内：
   保留 `**` 时不匹配，剥离后假匹配。

同类问题还有 2 处 `MISS_ATTRIBUTABLE`（AQ04、AQ07），剥离 Markdown 与空白后**完整命中**。

**这四条同源于一个设计约束：冻结评分器是纯字面代理，不做否定语义与 Markdown 归一化。**
本轮不修改冻结评分器；改进应作为**下一轮评测**的评分器工作，而不是生产 Prompt 改动。

### 检索层与生成层的分离

8 题关键事实上下文可得性**全部 `RETAINED`**（按本次真实上下文重算，未用 P9 历史 `contextAvailability`）。
因此本轮**没有**任何一题可归因为检索缺口，生成层也没有出错。

唯一的上下文信息边界在 AQ01：入选块**不含**版本生效日期或"原办法同时废止"条款，
模型只能从**文件名**（V1/V2）推断版本先后。生产 Prompt 确实把文件名放进 `<SOURCES>`，
所以这是模型**实际可见**的输入线索，不是外部知识泄漏。模型没有把四百元当作现行标准，
标注了"早期/现行或更新"，并用 `[推断]` 标记条件判断——在当前上下文中是正确且诚实的。

对照 AQ02：D3 补充通知正文**明确**写着"自2026年7月1日起，废止V2.0办法第六章中…条款"，
模型据此作出的版本辨别**有正文依据**。

**结论：要让模型不依赖文件名推断版本先后，应在来源选择层补入含施行/废止条款的块，
而不是改 Prompt。** 没有证据支持改生产 Prompt、检索算法、上下文预算或输出上限。

### 引用

8 题 `invalidCitationCount=0`、无强制拒答、原始回答与最终回答**逐字节相同**。
9 条引用**逐条**做了语义支持核对，全部支持其对应结论——未以"来源身份有效"代替"引用语义支持"。

### 人工复核边界

编码助手完成了上下文分析、3 项高严重度复核、2 处 `MISS_ATTRIBUTABLE` 复核与 9 条引用语义核对。
**独立人工复核 0 题**；未运行任何模型裁判。上述质量裁决应在独立人工完成后再次确认。

---

## 阶段一：只读核验与预检（真实 Chat 0 次）

阶段一结论只回答"真实生成的前置条件是否成立"。三个核心判断：

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

本轮已获授权并完成真实执行。后续最值得处理的问题按优先级：

1. **评分器（优先）**：字面 `contains` 无法处理否定语境与 Markdown 强调，是本轮唯一的假阳性来源。
   作为**下一轮评测**的评分器改进；**不修改冻结评分器**。
2. **AQ12 的 `abstentionPhrases` 覆盖度**：冻结列表未收录"尚未确定/并未明确"。同上属评分器改进。
3. **检索层（可选）**：AQ01 缺少版本施行/废止条款的正文块；应从来源选择层补入。
4. **独立人工复核**：6 项待确认语义判断（见 `manual-review.md`）。

没有证据支持改生产 Prompt、检索算法、来源选择参数、上下文预算、模型路由或输出上限。

完整证据：[README](../../acceptance-evidence/2026-10-26/p10-real-generation-02/README.md)、
[预检快照](../../acceptance-evidence/2026-10-26/p10-real-generation-02/preflight-summary.json)、
[调用账本](../../acceptance-evidence/2026-10-26/p10-real-generation-02/model-call-ledger.json)、
[语义审查](../../acceptance-evidence/2026-10-26/p10-real-generation-02/manual-review.md)。
