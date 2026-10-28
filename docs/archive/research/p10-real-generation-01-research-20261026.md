# P10-REAL-GENERATION-01 研究：传输准备缺口阻断质量测量

## 结论与研究边界

唯一真实客户端调用在 20,912 ms 后以 AI_MODEL_TIMEOUT 失败，没有模型响应。
后续 7 题遵守用户的失败即停规则，不自动重试；不据此评价模型事实能力、拒答、提示注入抵抗或引用语义支持。
这次预检漏核 Java JVM 出站代理，是测量准备缺口；不是已证实的生成模型错误。

## 实际调用链

冻结合成文件 → 生产 DocumentParser → DocumentChunker → 本地 Ollama Embedding →
DocumentIndexWriter（事务/代际校验） → pgvector → DocumentSearchService.search(topK=8) →
KnowledgeContextBuilder.buildBalanced(5 来源、8000 cp) → KnowledgeQuestionApplicationService.ask →
UserAiProviderService.resolve(userId, KNOWLEDGE_CHAT) → RoutingChatModelGateway → GeminiModelAdapter → JsonHttpModelClient。
传输超时使控制流在返回 ChatCompletionResult 前退出；KnowledgeCitationValidator 和 KnowledgePersistenceService.saveExchange 未到达。
生产流式路径源码也已检查，本轮没有另外运行 SSE 或 Agent。

测试用户配置按用户用途分配优先、默认模型回退规则读取；用户指定的 Gemini 专用分配未改动。
源库仅 SELECT 所选配置，密钥解密在内存中；配置密文临时存入一次性库，不导出凭据。
每题独立会话；所有业务数据为冻结合成语料，未复制实际用户文档或已有会话。
F4 按 P9 协议仅在 H-G07-NONE/H-G07-INJECT 等归属题临时索引；本轮在 AQ01 停止，未进入该阶段。

## 同次证据与可完成审查

AQ01 候选 8、入选 5；S1 的 document/chunk 身份及五百元正文在当次数据库核验，S2 的旧四百元冲突事实同样实际可见。
实际系统/用户 Prompt、Gemini 出站 JSON、SHA-256 和候选来源全部保存，没有用 P9 历史 availability 或语料全文替代本次上下文。
实际上下文包含 V1/V2 文件名版本线索，但所选块不包含施行日期/废止条款；此边界值得下次观察，尚不构成归因到模型的错误。
缺少模型回答时，机械 Rubric、引用逐条语义支持与独立人工答案审查均不执行。
本次未改变冻结评分器；测试自身的 capture/stop 和最终模型质量分开裁决。

## 输出预算与观测限制

现有 AiRequestOutputCap(1024) 将有效上限写入真实 generationConfig.maxOutputTokens，不修改持久配置 2048。
Gemini 非流式路径三参数 post 使用 maxAttempts=1，没有自动模型切换；错误后批次停止。
本地 Embedding 仍有既有最多三次传输尝试策略，与外部 Chat 不重试分开说明；外部 Embedding 不可替代。

AiOutboundObservation 标记放在 client.send 前，证明传输尝试真实执行，不证明 HTTP 请求体离开本机、Provider 已接收或已计费。
AI_MODEL_TIMEOUT 可来自 HttpTimeoutException 或 HTTP 408/504；此轮未独立捕获具体底层类别或 HTTP 状态。
没有 response/usage/finishReason，Token、费用、预算截断与实际响应模型版本均不可得，不作推算冒充实测。

## 7897 代理诊断

用户指出应走 7897 后，检查本机监听与新 JVM 的 ProxySelector：监听存在，但默认 Gemini 选路为 DIRECT。
真实 Surefire 记录未见 https.proxyHost/Port。显式经 127.0.0.1:7897 到 Gemini 域名的 CONNECT 和 TLSv1.3 握手成功，主机名验证启用。
诊断没有发送 API Key、Chat 或应用层 HTTP 请求。

这支持先修测试启动代理配置，但不等于确认原超时的全部根因；没有独立核实账号计费、额度或 Provider 接收。
下一轮应在模型调用前检查实际执行 JVM 的代理选择、记录脱敏网络方式，并保留失败/成功同一预算口径。
本轮没有增加输出上限或调整 Prompt/检索，也没有因代理可达而自动再调用模型。

## 验证与交付

首次全量 verify 的数据库连接错误原始回执不覆盖；定向复跑与授权后完整回归分别记账。
证据见 [README](../../acceptance-evidence/2026-10-26/p10-real-generation-01/README.md)、[代理检查](../../acceptance-evidence/2026-10-26/p10-real-generation-01/network-proxy-check.json)、[语义审查](../../acceptance-evidence/2026-10-26/p10-real-generation-01/manual-review.md)。
