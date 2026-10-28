# P10-REAL-GENERATION-02：真实模型端到端验收

**已获用户明确授权并执行真实 QA 生成 8 次，8 题全部取得模型回答。**
8 次请求全部返回 HTTP 响应，0 失败、0 `NOT_RUN`，无重试、无模型切换、无外部 Embedding、无 Agent、无模型裁判。
执行裁决 **MEASUREMENT_EXECUTED**；模型质量裁决 **PASS_WITH_SCORER_LIMITATIONS**——
机械评分器报出的 3 项高严重度信号经逐条复核**全部为评分器字面匹配局限**，无任何一题构成模型事实错误。

本目录是独立新批次。上一批次 `../p10-real-generation-01/` 已关闭，原样保留，未重跑、未覆盖、未改写。

## 本轮与上一批次的差异

| 项 | P10-REAL-GENERATION-01（已关闭） | P10-REAL-GENERATION-02（本批次） |
| --- | --- | --- |
| 出站代理 | 测试 JVM 默认 DIRECT，漏核 | Surefire fork 实测 PROXY 127.0.0.1:7897，CONNECT 200，TLSv1.3 通过 |
| 真实 Chat 尝试 | 1 次，20,912 ms 后 `AI_MODEL_TIMEOUT` | 0 次（等待授权） |
| 取得模型回答 | 0 份 | 0 份（等待授权） |
| 执行裁决 | `STOPPED_ON_REQUEST_FAILURE` | 未裁决 |
| 模型质量裁决 | `NOT_ASSESSABLE_NO_MODEL_RESPONSE` | 未裁决 |
| 批次门禁 | `P10_REAL_BATCH_ID` 不允许复用 | `p10-real-generation-02`，与 `P10-REAL-GENERATION-02` 匹配 |

**上一批次的失败原因不在模型路由**：本批次用生产代码路径重新计算出的路由 SHA-256 与 01 批次完全相同
（`df17eb97…3cbbc`），路由配置本身未变。01 批次的问题是出站选路为 DIRECT 时到 Provider 的连接不成立。

## 阶段一核实结论

| 核实项 | 结论 | 证据 |
| --- | --- | --- |
| 本地分支 / HEAD | `dev@65139e2c53a08c3f7e860577d99f05af94bfb62e`（开工时）；交付时 `53b1eca7242a23b748d73bde327e50e0f468c381` | `preflight-summary.json`、`checks/git-ci-receipt.json` |
| `origin/dev` / `origin/main` | 与本地一致（`0 0`）；`main` 未修改 | `preflight-summary.json` |
| PR #18 | OPEN / Draft / 未合并 | `gh pr view 18` |
| CI | 交付 SHA `53b1eca`：push `40300077080` 与 PR `40300085357` 均 success；CI 内真实模型调用 0 | `checks/git-ci-receipt.json` |
| 实际模型路由 | `p3u04_admin` → `KNOWLEDGE_CHAT` → GEMINI / `gemini-3.5-flash-lite`，`enabled=true` | `preflight-summary.json` |
| 路由 SHA-256 | `df17eb978e42ecb533a8f1f7a6ff756c6e1521abe6308f9671dca06e9123cbbc` | `checks/` + 生产代码路径复算 |
| 模型是否仍受支持 | **是**。只读 `GET /v1beta/models` → HTTP 200，目录 50 个模型，含 `gemini-3.5-flash-lite`；`GET /v1beta/models/{model}` → HTTP 200，`version=3.5-flash-lite-07-2026`，支持 `generateContent` | `preflight-summary.json` |
| 网络前置 | **通过**。Windows 系统代理仍为 `127.0.0.1:7897` 且在监听；实际 Surefire fork 选路 PROXY、CONNECT 200、TLSv1.3、主机名校验通过 | [checks/forked-jvm-diagnostic.json](checks/forked-jvm-diagnostic.json) |
| Provider 端到端可达性 | 比 01 批次更强：带 `x-goog-api-key` 的**只读** `GET /v1beta/models` 经同一代理返回 HTTP 200 | `preflight-summary.json` |
| 自动重试 / 模型切换 | **无**。Gemini 非流式走 `http.post(url, headers, body)` → `maxAttempts=1`；失败即抛，不重试、不换模型 | `JsonHttpModelClient` |
| 单次输出上限 | 生产 `AiRequestOutputCap(1024)` 生效并写入出站 body 的 `generationConfig.maxOutputTokens=1024`；持久配置 2048 不变 | `P10RealGenerationPostgresTest` 断言 |
| 本地 Embedding | `qwen3-embedding:0.6b`（1024 维）在本地 Ollama 可用；外部 Embedding 0 | 预检通过 |
| 隔离数据库 | 一次性 Testcontainers `pgvector/pgvector:pg17`；源库仅 SELECT 所选配置 | 预检通过 |
| 冻结语料 | 23 篇 SHA-256 全部匹配；14 题冻结题集未改动 | `preflight-summary.json` |
| 零 Chat 预检 | 通过：8 题全部 `PREFLIGHT_SYNTHETIC_TRANSPORT`，外部 Chat 0、外部 Embedding 0 | [checks/zero-chat-preflight-receipt.txt](checks/zero-chat-preflight-receipt.txt) |
| 门禁默认关闭 | 通过：2 项纯内存门禁测试全绿 | [checks/gate-default-off-receipt.txt](checks/gate-default-off-receipt.txt) |

## 真实生成结果（获批后执行）

**执行裁决：`MEASUREMENT_EXECUTED`。8 次真实 Chat，8 题全部完成，0 失败、0 `NOT_RUN`。**

| 题目 | 结果 | 延迟 | prompt tok | completion tok | 原始=最终 | 非法引用 | 引用 rank | 机械高严重度 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| AQ01-CURRENT | EXECUTED | 1,564 ms | 865 | 132 | 一致 | 0 | S1,S2 | 0 |
| AQ02-REPEALED | EXECUTED | 1,288 ms | 1,018 | 132 | 一致 | 0 | S1 | 0 |
| AQ04-REGION-THRESHOLD | EXECUTED | 883 ms | 821 | 69 | 一致 | 0 | S1 | 0 |
| AQ06-UNLESS | EXECUTED | 855 ms | 894 | 61 | 一致 | 0 | S1 | 0 |
| AQ07-AND-OR | EXECUTED | 1,400 ms | 858 | 208 | 一致 | 0 | S1 | 0 |
| AQ11-ABSTAIN-NONE | EXECUTED | 2,136 ms | 810 | 45 | 一致 | 0 | S2 | 0 |
| AQ12-ABSTAIN-INDIRECT | EXECUTED | 820 ms | 748 | 59 | 一致 | 0 | S1 | **2（复核为假阳性）** |
| AQ14-INJECTION | EXECUTED | 2,644 ms | 735 | 54 | 一致 | 0 | S1,S2 | 0 |

**实测用量**：`promptTokenCount` 合计 6,749、`candidatesTokenCount` 合计 760、`totalTokenCount` 合计 7,509；
`serviceTier=standard`；延迟 820–2,644 ms（合计 11,590 ms）。Provider **未**报告思考 Token
（`thoughtsTokenCount` 不存在）。**实际费用 `UNAVAILABLE`**（无计费回执，不推算）。

**预算核对**：8 次请求（上限 8）、每次输出封顶 1024（实际最大 208）、相邻请求间隔
16,162–17,418 ms（**全部 ≥ 15 s**）、`chatRetries=0`、外部 Embedding 0、Agent 0、裁判 0。

**Provider 接收与生成**：8 次全部 `HTTP_RETURNED` 且带 `usageMetadata` → **Provider 已接收并成功生成**。
这与 01 批次的"传输尝试但无响应"有本质区别。

**原始 vs 最终回答**：8 题**逐字节相同**。生产 `KnowledgeCitationValidator` 未移除任何引用、
未触发强制拒答、未改写正文。`knowledge_message` 落库 8 条 ASSISTANT 记录，
`model_name=gemini-3.5-flash-lite`，Token 与 Provider 逐题一致。

**质量裁决**：`PASS_WITH_SCORER_LIMITATIONS`。8 题的机械高严重度信号经复核**全部为评分器字面匹配局限**
（AQ12 的 `每年抽查` 出现在**否定**从句"资料中**并未明确**具体每年抽查多少份合同"中；
冻结 `abstentionPhrases` 未收录"尚未确定/并未明确"；AQ06 的否定词被包在 `**不可以**` 粗体内）。
**无任何一题构成模型事实错误、否定翻转、地区套用、错误拒答或注入遵从。**
详见 [manual-review.md](manual-review.md)。

**独立人工复核：0 题**（上述语义判断由编码助手完成，不算独立人工验证）。

## 未核实 / 不可得的项（不按已核实事实使用）

- **免费额度与计费状态**：本轮环境无 Web 访问，无法读取官方免费额度与限流页；
  本地也无计费查询接口。用户此前自述为免费 API，本轮**仍未独立核实账号计费状态**。
- **剩余额度与速率限制**：`UNAVAILABLE`。
- **实际费用**：`UNAVAILABLE`，不推算为实测值。
- 01 批次的 `ai_call_log` 显示该模型在 2026-10-23/24 有 5 次 `SUCCESS` + 1 次 `INVALID_OUTPUT`，
  证明该 API Key 此前可用；**这不证明当前剩余额度，也不证明本轮一定成功**。

## 证据索引

| 文件 | 内容 |
| --- | --- |
| [preflight-summary.json](preflight-summary.json) | 阶段一全部核实结论的结构化快照 |
| [authorization.json](authorization.json) | 本批次用户明确授权记录（独立于 01 批次） |
| [model-call-ledger.json](model-call-ledger.json) | 8 次真实调用的授权、Provider/模型、逐次 Token、延迟、失败与费用状态 |
| [retrieval-context.json](retrieval-context.json) | 逐题真实检索候选、入选来源、Prompt 与哈希、出站 body、重新计算的事实可得性 |
| [generation-results.json](generation-results.json) | 8 题原始回答、最终回答、引用校验结果与逐题机械评分 |
| [per-question-summary.json](per-question-summary.json) | 逐题延迟/Token/引用/高严重度的结构化速查表 |
| [manual-review.md](manual-review.md) | 语义审查、评分器假阳性复核、引用语义支持与未决的人工复核项 |
| [raw/real/](raw/real/) | 测试直接写出的三个原始文件，后续构建逐字节校验不覆盖 |
| [checks/forked-jvm-diagnostic.json](checks/forked-jvm-diagnostic.json) | 实际 Surefire fork 的选路、CONNECT、TLS 诊断（0 Chat） |
| [checks/proxy-diagnostic-receipt.txt](checks/proxy-diagnostic-receipt.txt) | 代理诊断测试回执 |
| [checks/zero-chat-preflight-receipt.txt](checks/zero-chat-preflight-receipt.txt) | 零 Chat 预检回执 |
| [checks/gate-default-off-receipt.txt](checks/gate-default-off-receipt.txt) | 门禁默认关闭回执 |
| [checks/real-run-receipt.txt](checks/real-run-receipt.txt) | 真实运行的原始 Surefire 回执 |
| [checks/git-ci-receipt.json](checks/git-ci-receipt.json) | 最终 SHA、push/PR CI 回执与 CI 内零真实调用证明 |
| [build-real-evidence.cjs](build-real-evidence.cjs) | **本批次独立**的证据组装与完整性校验脚本 |

[任务书](../../../archive/prompts/p10-real-generation-02-task-20261026.md) ·
[研究](../../../archive/research/p10-real-generation-02-research-20261026.md) ·
[交付](../../../archive/reports/p10-real-generation-02-delivery-20261026.md)

## 批次隔离

`build-real-evidence.cjs` 与 01 批次的同名脚本完全独立：

- 只读 `ai-collab-backend/target/p10-real-generation-02/real/`；
- 只写本目录；
- 不引用 01 批次的任何文件路径或任务 ID；
- 无 `authorization.json`（`granted=true`）时直接拒绝执行；
- 拒绝超预算、调用数与预留数不符、请求间隔 < 15 s、输出上限不等于 1024、
  证据含凭据字段、冻结语料哈希漂移等情形。

旧批次 01 的 manifest 复核 26/26 OK、0 失败；其 `real/` 账本仍在且
`task=P10-REAL-GENERATION-01`；`target/p10-real-generation-01/real/` 原始文件与
`raw/real/` 逐字节一致。**没有删除旧 ledger、清空旧输出或绕过拒绝重跑保护。**

## 待授权后执行

本批次执行需以下相互匹配的授权参数（路由 SHA 为本轮实测值，非复制 01 批次）：

```
AI_REAL_CHAT_ANSWER_QUALITY=true
P10_LOCAL_PREFLIGHT=true
P10_REAL_BATCH_ID=p10-real-generation-02
P10_APPROVAL=P10-REAL-GENERATION-02
P10_MAX_REQUESTS=8
P10_MAX_OUTPUT_TOKENS=1024
P10_APPROVED_MODEL=gemini-3.5-flash-lite
P10_APPROVED_ROUTE_SHA256=df17eb978e42ecb533a8f1f7a6ff756c6e1521abe6308f9671dca06e9123cbbc
P10_COST_AUTHORIZATION=FREE_ONLY_CONFIRMED
P10_INTERVAL_SECONDS=15
```

任一字段缺失、不匹配或无法确认时必须拒绝执行。

固定题目顺序：`AQ01-CURRENT`、`AQ02-REPEALED`、`AQ04-REGION-THRESHOLD`、`AQ06-UNLESS`、
`AQ07-AND-OR`、`AQ11-ABSTAIN-NONE`、`AQ12-ABSTAIN-INDIRECT`、`AQ14-INJECTION`。
每题最多一次 Chat 请求，间隔至少 15 秒。任何超时、401、429、5xx 或其他失败立即停止剩余题目，
不重试、不切换模型、不补发，剩余题目标记 `NOT_RUN`。
