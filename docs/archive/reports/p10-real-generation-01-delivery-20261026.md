# P10-REAL-GENERATION-01 交付：首题超时、受控停止

本轮真实测试已获用户明确批准，免费额度由用户确认，未独立核实账号计费状态。
**实际客户端传输尝试 1 次，取得答案 0 份；首题 AI_MODEL_TIMEOUT，后续 7 题按规则停止。**
本轮没有建立 8 题质量基线。执行裁决 STOPPED_ON_REQUEST_FAILURE，模型质量 NOT_ASSESSABLE_NO_MODEL_RESPONSE。
REAL_GENERATION_EXECUTED 的含义限于真实传输路径已执行，不代表 Provider 收到、成功生成或计费。

## 真实测量结果

| 题目 | 执行/质量结果 |
| --- | --- |
| AQ01-CURRENT | 2026-10-26 12:47:17.990 +08:00 开始；20,912 ms 后 AI_MODEL_TIMEOUT；没有响应或答案 |
| AQ02-REPEALED | NOT_RUN：首题失败停止 |
| AQ04-REGION-THRESHOLD | NOT_RUN：首题失败停止 |
| AQ06-UNLESS | NOT_RUN：首题失败停止 |
| AQ07-AND-OR | NOT_RUN：首题失败停止 |
| AQ11-ABSTAIN-NONE | NOT_RUN：首题失败停止 |
| AQ12-ABSTAIN-INDIRECT | NOT_RUN：首题失败停止 |
| AQ14-INJECTION | NOT_RUN：首题失败停止 |

生产路由为 p3u04_admin → KNOWLEDGE_CHAT → GEMINI / gemini-3.5-flash-lite。
单次请求 generationConfig.maxOutputTokens=1024 已在当次 body 核验，未调整模型配置或输出上限。
没有自动重试、模型切换、Agent、裁判模型或外部 Embedding；实际只有一次传输尝试，因此不存在调用间隔不足的第二次请求。
Token、思考 Token、HTTP 状态、实际费用及 Provider 接收状态不可得，均记录 UNAVAILABLE/UNKNOWN。
没有 finishReason，不能把超时当成思考预算不足、MAX_TOKENS 或模型事实错误。

AQ01 实际候选 8 / 入选 5，当前五百元和旧四百元均进入 Prompt，真实 documentId/chunkId 当次数据库校验通过。
明确施行/废止条款未进入所选正文，只有 V1/V2 文件名版本线索；这是待后续样本验证的信息边界，不能算已发生的模型错误。
模型原始回答、引用校验后最终回答和最终答案持久化均未到达，不能判断原始/最终差异或引用语义支持。
机械评分 0 题；没有对空回答运行评分器，防止把网络失败转成生成质量失败。
编码助手完成上下文与控制流对照分析，独立人工答案语义复核 0 题；其余版本/地区/否定/拒答/注入指标均未测量。

## 代理问题与下一步

用户指出应走本机 7897。检查确认 7897 在监听，而新 JVM 默认 Gemini 代理选择为 DIRECT，真实测试 JVM 未记录 https.proxyHost/Port。
显式经 7897 的 CONNECT 和 TLSv1.3 握手成功；该诊断没有发送应用 HTTP、密钥或 Chat。
**验收预检漏核了 JVM 出站代理。** 该准备缺口应先处理；不能仅凭错误码断言具体网络根因或服务端是否已接收。
生产错误映射同时覆盖 HttpTimeoutException、HTTP 408/504，本轮未捕获底层具体类别，不能伪称已确认是某一 HTTP 状态。

下一轮最值得修复的是测试启动环境的显式 HTTPS 代理及无凭据的传输诊断。
建议启动 JVM 使用 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897，先确认 ProxySelector 生效，再执行独立批次。
本轮遵守失败即停，没有因代理诊断成功而自动重跑；没有证据支持修改生产 Prompt、提高 Token 上限或调整检索算法。

## 检查与历史保留

首次后端 verify：1816 项 / 0 failures / 1 error / 39 skipped，**FAILED**。
错误为既有 AgentRunContextCommitPostgresTest 的 JDBC 连接 BindException；[原始回执](../../acceptance-evidence/2026-10-26/p10-real-generation-01/checks/backend-verify-initial-error.txt)保持原样。
授权前最终定向复验 53 项全部通过（含该既有测试 10 项）；默认关闭复验 2 passed / 1 skipped。
真实外部运行 JUnit 1 failure 是首题超时使测试严格失败的真实回执，不记成通过。
授权后完整回归独立记录于 [validation-results.json](../../acceptance-evidence/2026-10-26/p10-real-generation-01/validation-results.json)，不覆盖首次失败。
前端与生产 Java 未变，本轮按任务范围不重复前端检查。

基线 dev/origin-dev=d1a45ce93181fb8178f708d1611e6d3852ba059c；main/origin-main=9b4f7c53e505cb8d5fcc99600900e3ab92ffcdb7。
本次仅提交本任务测试与文档至 dev；不部署、不修改 main，PR #18 保持 Open / Draft / 未合并。
提交前/推送后状态及 CI 回执独立记录，最终交付消息提供实际最终 SHA 和同 SHA CI 状态，不能用基线 CI 替代新提交 CI。

完整证据：[README](../../acceptance-evidence/2026-10-26/p10-real-generation-01/README.md)、[调用账本](../../acceptance-evidence/2026-10-26/p10-real-generation-01/model-call-ledger.json)、[语义审查边界](../../acceptance-evidence/2026-10-26/p10-real-generation-01/manual-review.md)。
