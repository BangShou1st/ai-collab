# P10-REAL-GENERATION-01：真实 QA 生成基线

**已获用户明确授权并执行真实客户端传输尝试 1 次；首题超时，按约定停止。**
计划 8 题：AQ01 FAILED（AI_MODEL_TIMEOUT，20,912 ms），其余 7 题 NOT_RUN。
Provider 是否接收请求、是否产生用量未知；没有模型响应、原始回答或最终落库回答。
执行裁决 **STOPPED_ON_REQUEST_FAILURE**；模型质量 **NOT_ASSESSABLE_NO_MODEL_RESPONSE**。
REAL_GENERATION_EXECUTED 仅表示真实客户端出站路径已执行，不表示成功生成答案。

## 本轮事实

- 用户/用途/模型：p3u04_admin → KNOWLEDGE_CHAT → GEMINI / gemini-3.5-flash-lite，未切换。
- 用户已确认使用免费 API；**未独立核实账号计费状态**，未开启付费或升级档位。费用与 Token 均 UNAVAILABLE。
- 硬预算：最多 8 次、每题最多一次、单次最多 1024 输出 tokens；实际仅 1 次客户端传输尝试。
- 外部 Embedding / Agent / 模型裁判 / 自动 Chat 重试均为 0。首题失败即停止，没有后续请求，15 秒间隔不产生额外样本。
- AQ01 实际检索 8 个候选，生产 buildBalanced 保留 5 个来源；当前五百元和旧四百元都进入 Prompt。
- 来源 documentId/chunkId 已在当次一次性数据库核验。模型正文引用、逐条支持、validator 和最终答案持久化均未到达，不能称引用质量通过。
- 没有响应 finishReason 或 usage，无法判断是否涉及输出/思考 Token；不把本次超时归为预算截断或事实错误。

## 代理排查与责任边界

用户指出应使用 7897。后续只读检查确认：本机 7897 在监听；新 JVM 的 ProxySelector 对 Gemini 返回 DIRECT，实际测试 Surefire 元数据未记录代理 host/port 属性。
显式经 127.0.0.1:7897 建立到 Gemini 域名的 CONNECT 隧道和 TLSv1.3 握手成功。
该诊断没有发送应用层 HTTP 请求、API Key 或 Chat 请求。

**本次预检漏核 JVM 出站代理，这是验收准备缺口。** 现有证据支持优先排查出站代理，不能独立证明超时一定由此造成，也不能证明 Provider 是否已接收。
生产错误映射将 HttpTimeoutException 与 HTTP 408/504 统一为 AI_MODEL_TIMEOUT，本轮没有捕获底层 HTTP 状态或异常类别；不得编造为 429/401、模型拒绝或推理过慢。

本轮遵守用户设定的失败即停；没有修代理后自动重跑。后续批次启动 JVM 时应显式使用
`-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897`，不更换用户模型路由。

## 证据索引

| 文件 | 内容 |
| --- | --- |
| [authorization.json](authorization.json) | 用户明确授权、免费 API 自述、预算及禁止付费约束 |
| [model-call-ledger.json](model-call-ledger.json) | 1 次传输尝试、超时、Token/费用不可得与 Provider 接收未知 |
| [retrieval-context.json](retrieval-context.json) | 当次真实候选、来源、Prompt、出站 body、哈希及重新计算的事实可得性 |
| [generation-results.json](generation-results.json) | 1 FAILED / 7 NOT_RUN；没有回答，不虚构评分 |
| [raw/real/](raw/real/) | 测试直接写出的三个原始文件，后续构建逐字节校验不覆盖 |
| [manual-review.md](manual-review.md) | 可完成的上下文审查与未完成的答案语义/独立人工复核 |
| [network-proxy-check.json](network-proxy-check.json) | DIRECT、7897 监听与无 Chat 的 CONNECT/TLS 诊断 |
| [checks/real-run-timeout-original.txt](checks/real-run-timeout-original.txt) | 真实运行失败的原始 Surefire 回执 |
| [checks/backend-verify-initial-error.txt](checks/backend-verify-initial-error.txt) | 首次全量 verify 的数据库连接错误，保留历史 |
| [preflight-summary.json](preflight-summary.json)、[preflight/](preflight/) | 授权前快照；合成传输不参加真实生成评分 |
| [validation-results.json](validation-results.json) | 首次失败、定向复跑、授权后回归分开记账 |
| [manifest.sha256](manifest.sha256) | 本轮证据完整性；不含 manifest 自身 |

[任务书](../../../archive/prompts/p10-real-generation-01-task-20261026.md) ·
[研究](../../../archive/research/p10-real-generation-01-research-20261026.md) ·
[交付](../../../archive/reports/p10-real-generation-01-delivery-20261026.md)

## 路径、隔离与复现边界

一次性 Testcontainers pgvector 数据库，随机项目；源数据库只读查询所选用户配置。
主密钥/密文仅进程内及一次性配置表使用，不导出凭据。合成文件经生产解析、分块、本地 Ollama qwen3-embedding:0.6b（1024 维）和 DocumentIndexWriter 入库。
未覆盖上传 HTTP/MinIO 对象搬运；没有使用真实用户业务文档。F4 按 P9 协议仅在归属题临时 READY；本轮因 AQ01 停止，F4 没有被注入真实题目。

真实调用保持生产 QA search(topK=8) → buildBalanced(5/8000 code points) → KnowledgeQuestionApplicationService → 用户用途路由 → GeminiAdapter → JsonHttpModelClient。
同次 Prompt/来源/出站 body 已采集；由于超时，后续 KnowledgeCitationValidator / saveExchange 不可测量。
本地 Embedding 使用既有最多三次传输尝试策略，Chat 单次尝试且不重试；不允许外部 Embedding 替代。

默认 CI 整类跳过真实测试，纯内存 P10RealGenerationGateTest 仍默认运行。
已有真实 ledger 时拒绝再次运行；**不要删除 ledger 绕过保护**。
授权前 build-preflight-evidence.cjs 在存在明确授权后拒绝覆盖；本轮真实证据可用以下离线命令重建（不请求模型）：

`node docs/acceptance-evidence/2026-10-26/p10-real-generation-01/build-real-evidence.cjs`

本轮生产 Java、Prompt、检索参数、P9/P10 冻结语料/题集/评分器均未修改；不部署、不修改 main、不合并 PR #18。
