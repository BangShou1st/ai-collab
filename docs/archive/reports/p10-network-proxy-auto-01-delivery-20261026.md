# P10-NETWORK-PROXY-AUTO-01 交付报告

本机已用新 Surefire fork 和 dev 独立 Java 验证 Windows 原生系统代理选路、CONNECT 200 和经证书/域名校验的 TLSv1.3。没有发送新的真实 Chat，外部 Embedding/Agent/模型裁判均 0；没有开启付费、部署、修改 main 或操作 PR #18 状态。

原 common JsonHttpModelClient 未强制直连；启动时未启用 JDK 原生系统代理发现，加上 JDK 不自动消费进程 HTTPS_PROXY，使旧 JVM 默认 DIRECT。该缺口解释了选路，不足以证明旧 AQ01 超时的唯一原因，更不能证明模型错误或 Provider 是否收到请求。

实际改动：dev、Maven 父/Boot run/fork 在 JVM 启动前启用 java.net.useSystemProxies；客户端共享选择器保留 JDK 解析 OS/JVM 显式配置，补上 loopback 排除及受校验 ENV 回退。全程没有硬编码本机端口、扫描端口、解析特定代理软件或替换全局 ProxySelector。同步、SSE、模型发现共用策略，Zen 移除专用强制直连，Kilo 使用已有共享库 HttpClient 入口，旧 Chat/Embedding、本地 Ollama/MinIO 一并保护。旧 Chat 兼容 JDK 超时异常，既有错误码/重试开关不变。

回归：全量后端 verify 1830 项、0 失败/错误、40 跳过；后续最终源文件定向 verify 113 项、0 失败/错误、2 跳过；Maven 显式参数实际 fork 2 项通过；外部零 Chat fork 诊断 1 项通过。首次定向 SSE 夹具断言失败和旧 JaCoCo 类版本警告均单独保留，后续成功不覆盖失败。最终提交树由 CI 再验证。

使用：普通 dev 新启动自动生效，旧 JVM 需重启一次；Maven 自动传入 fork/run，IDE 直接启动需 VM options 加 -Djava.net.useSystemProxies=true；无需手填代理地址/端口。无配置允许 DIRECT；选中代理失败不会回退直连，本地排除是显式安全策略。HTTPS、SSRF、默认 TLS/域名和禁止重定向没有退化。本轮未接管或重启用户现有应用进程。

限制：ENV URL 不支持凭据/SOCKS/HTTPS 代理和 CIDR；NO_PROXY 仅作用 ENV 回退。JDK 的原生 DIRECT 无法区分 OS 未配置与 PAC 排除，混用 ENV 时必须按指南明确 NO_PROXY。代理 DNS/egress 仍需信任边界控制。CONNECT/TLS 成功不替代 Provider 接收、免费额度、模型可用或回答质量验证。

下一步可以安全准备新的真实验收批次，继续原免费额度、8 次/1024 tokens/至少 15 秒及失败即停限制；新批次账本/审批/预算门禁独立，旧 01 已关闭且不可重用。本专项未自动发起新批次。

旧证据按基线 dev@fca8860a4f9f83e726f532985db83214bed43c16 逐文件及原 manifest 校验，不改旧 ledger、AQ01 超时、后七题 NOT_RUN 或首轮 Maven 数据库连接错误。

- [网络机制和使用指南](../../development/model-network-proxy.md)
- [完整证据与回执](../../acceptance-evidence/2026-10-26/p10-network-proxy-auto-01/README.md)
- [本轮调用账本](../../acceptance-evidence/2026-10-26/p10-network-proxy-auto-01/call-ledger.json)
- [旧证据完整性](../../acceptance-evidence/2026-10-26/p10-network-proxy-auto-01/old-evidence-integrity.json)

仅提交/推送 dev；最终提交 SHA、push/PR CI 状态及线上链接以最终交付答复的实际查询为准，不在提交前伪造尚未发生的 CI 成功。
