# P10-NETWORK-PROXY-AUTO-01

结论：本机 Windows 原生系统代理已在新的实际 Surefire fork 和 dev 入口 Java JVM 中识别，Gemini 目标选中 PROXY，CONNECT 200，TLSv1.3 证书/域名验证成功。**本专项真实 Chat / 外部 Embedding / Agent / 模型裁判均为 0。** 不代表 Provider 已接收请求、免费额度可用或模型生成成功。

基线 dev@fca8860a4f9f83e726f532985db83214bed43c16。用户模型路由、生产 Prompt、检索算法、冻结题集、评分标准不变；不部署，不操作 main，不合并或改变 PR #18 Draft。

## 原因与最小修复实验

原 JsonHttpModelClient 使用 JDK 默认选路，没有显式 NO_PROXY。旧启动入口未传原生系统发现开关，而 JDK 默认不读取 HTTPS_PROXY；不能把 DIRECT 归因于该业务客户端强制直连。另查到 Zen 专用旧传输显式 NO_PROXY，本次一起移除。

先验证仅在 JVM 启动前启用 java.net.useSystemProxies=true：原生 JDK 已能识别 Windows 代理，但当前机器对数字 127.0.0.1 也返回 HTTP 代理，显式 http.nonProxyHosts 仍未改变该原生结果。JDK 原生分支不覆盖进程 HTTPS_PROXY/NO_PROXY 语义。由此才增加客户端级最小适配：复用原生 OS 选择，保护本地访问，并支持经过校验的显式 ENV 回退；不解析注册表/PAC，不扫描端口，不设置全局 ProxySelector。

| 证据 | 含义 |
| --- | --- |
| [native-selector-disabled.json](native-selector-disabled.json) | 新 JVM 关闭原生发现时，原生选择 DIRECT；只选路，无连接 |
| [native-selector-enabled.json](native-selector-enabled.json) | 新 JVM 启用发现并设置 nonProxyHosts 后，原生识别代理及数字 loopback 差异；只选路 |
| [NativeProxyProbe.java](NativeProxyProbe.java) | 上述观察的只读 JDK 源文件启动器，无模型依赖 |
| [forked-jvm-diagnostic.json](forked-jvm-diagnostic.json) | 实际 Surefire fork，启动参数/属性、原生识别、目标 PROXY、CONNECT/TLS 分阶段 |
| [dev-launcher-diagnostic.json](dev-launcher-diagnostic.json) | dev.ps1 diagnose-proxy 实际独立 Java，使用普通后端同一启动开关与共享构造器 |
| [standalone-selection.json](standalone-selection.json) | 独立 Java/IDE 入口选路；没有把它标记为 CONNECT/TLS 已验证 |
| [verification.json](verification.json) | 每轮 Maven 回执、失败与后续独立成功记录、范围差异 |
| [call-ledger.json](call-ledger.json) | 零模型调用、3 次目标 CONNECT/TLS 探针、费用核实边界 |
| [old-evidence-integrity.json](old-evidence-integrity.json) | 旧批次所有文件与基线 Git blob 逐字节一致、旧 manifest 校验 |
| [source-snapshot.json](source-snapshot.json) | 本轮本地源码快照，哈希基于工作区字节 |
| [git-ci-receipt.json](git-ci-receipt.json) | 实现提交实际 push/PR CI 成功、PR Draft/main 状态，最终文档提交单独核实 |
| [manifest.sha256](manifest.sha256) | 本目录文件 SHA-256，排除 manifest 自身 |

当前观察到的本地代理端口仅记录在诊断 JSON，是系统选择结果；未写入项目默认值。CONNECT/TLS 探针使用实际客户端选中的策略及 JDK 默认 TLS socket，不发送 Provider 应用层 HTTP、API Key 或 Prompt；它不是一次成功的 Chat 请求。

普通 dev 新启动、Maven Wrapper/Boot run 与 Surefire 均在 JVM 启动前启用系统发现。IDE/普通 java -jar 需在 VM options 首次配置 -Djava.net.useSystemProxies=true；无需填写代理地址。**未重启/接管用户现有后端进程**，已运行的旧 JVM 需按入口规则重启一次才应用新启动参数。新 dev 诊断 JVM 与正常 Spring 服务不是同一进程，不将此冒充已重启应用。

## 确定性回归与失败历史

最终受影响范围 verify：113 项，0 失败、0 错误，2 项跳过（外部诊断默认关闭、显式 Maven 参数专用例默认关闭）。覆盖：JVM/原生/ENV 优先级、无配置 DIRECT、NO_PROXY、本地排除、错误配置脱敏、不可达代理不能回退已可达直连源站、真实同步/SSE/GET 假代理传输、HTTPS/SSRF/禁止重定向、默认信任拒绝自签及证书域名不匹配、本地 Ollama、Kilo/Zen、旧 Chat 超时错误码与禁重试、P10 新批次门禁。

额外 offline Maven 专用例：2 项通过；在真正 Surefire fork 中验证父命令显式 HTTPS 代理属性与原生 false 启动开关，目标仅 .invalid 选路，不连接该测试端口。

最后补强 Kilo 应用配置与共享库自动配置共同加载的集成断言，4 项独立通过：应用提供的 SDK 客户端确实持有统一选择器和 Redirect.NEVER，禁用开关仍移除客户端，未改变库默认模型/免费模式/匿名行为。此为测试断言补强，没有再改生产代码。

全量后端 verify：1830 项，0 失败、0 错误、40 项跳过。随后增加了旧 Chat 超时兼容分支/测试和显式 fork 参数测试；最终 113 项 verify 重新编译、打包这些最终源文件，CI 检查提交树的全量测试。首轮全量覆盖率读取历史 target/jacoco.exec 出现旧类版本警告；后续定向 verify 使用独立 JaCoCo 文件，没有该警告。均保留原回执，不覆盖历史。

首次定向 81 项出现 1 个**测试夹具断言失败**：SSE 的 DONE 帧被计为 JSON 内容。修正夹具分别计数后 82 项通过，不把夹具失败归因于模型或网络；原始失败见 [checks/targeted-01-failure-original.txt](checks/targeted-01-failure-original.txt)。默认 CI 不运行外部探针/真实模型测试；所有新普通测试只用隔离本地服务或纯选择器。

上述文件是原始 Surefire 单类失败报告；另保留逐字节完整 [Maven 首轮 stdout](checks/targeted-01-original-stdout.txt)，两者是不同输出，不覆盖任一文件。实现提交的 CI 全量后端已确认 1832 项、0 失败/错误、41 跳过，见 [CI 后端回执](checks/ci-implementation-backend-receipt.txt)；push/PR 均成功。

本轮没有前端实现改动；正常 CI 仍验证前端。所有 Maven 状态回执位于 checks/，本地任务日志交付前清理，原始失败另存受控 txt。

## 使用、边界与下一批

机制设计、Windows/Maven/IDE 命令、优先级、覆盖范围和限制见 [模型网络指南](../../../development/model-network-proxy.md)。固定 loopback 排除优先；JVM 显式规则 > 原生已选代理 > ENV 回退 > 无配置 DIRECT。代理失败不轮转/直连。证书/域名、SSRF 和 Redirect.NEVER 保留，未改出站策略。

ENV 只支持无凭据的 HTTP 代理 URL，以及文档列明的 NO_PROXY 语法，不支持认证/SOCKS/CIDR。原生 DIRECT 无法区分“无 OS 配置”和“OS/PAC 主动绕过”，有 ENV 时应在 NO_PROXY 明确排除；不能声称完整复现企业 PAC 行为。可信代理仍是 DNS/egress 的安全边界。

[旧 P10 01 批次](../p10-real-generation-01/README.md) 的首题 20.912 秒超时、后七题未执行、首次 Maven 数据库连接失败、账本和所有原始证据完整保留。旧报告的手填端口建议为历史记录，本专项指南取代其后续启动建议，不能回写旧文件。

新的网络前置检查已通过，可以准备**独立新批次**；必须使用新的 P10_REAL_BATCH_ID、匹配的大写 P10_APPROVAL 和独立预算/免费额度门禁，旧 01 标识直接拒绝，已有账本仍拒绝重跑。用户免费 API 自述保留，未独立核实计费。本专项不续跑旧 8 题，也不评价无回答的模型质量。

离线组装脚本：node 本目录/build-evidence.cjs，首次组装需要尚未清理的本地原始输出；已有账本时拒绝重建。文档补充后仅可 --manifest-only 更新文件清单，不发送任何网络请求。Git/CI 在线最终回执以交付报告及最终答复所列真实 SHA 为准。
