# 模型 HTTP 网络代理

本规范适用于 Java 21、P10-NETWORK-PROXY-AUTO-01。代理选路不是 Provider 路由，不改用户的模型用途分配、模型或凭据。

## 启动与使用

Windows 推荐入口 `./dev.ps1 start` 在启动后端 JVM 前传入 `-Djava.net.useSystemProxies=true`，无需填写代理服务器或端口。
已有 JVM 不会因源码更新改变启动时缓存的发现开关；入口若复用旧进程，需按其进程管理规则重启一次。不要在 Spring Bean 初始化后 System.setProperty 代替启动参数。

Maven Wrapper 的 `.mvn/jvm.config` 为 Maven JVM 启用原生发现。
POM 为 Spring Boot run 的独立 JVM、Surefire fork 的启动 argLine 分别传入开关，保留 JaCoCo 的晚绑定 argLine。
仅 Maven 父进程或 systemPropertyVariables 显示 true 不能证明子 JVM 在启动时使用了开关。
显式 `-Djava.net.useSystemProxies=false` 可覆盖 POM 的测试/Boot run 参数；显式 http(s).proxyHost/Port 由 JDK 按其协议规则处理。

IntelliJ / Eclipse / VS Code 直接运行 AiCollabBackendApplication：在 **VM options**（不是 Spring 参数）设置：

`-Djava.net.useSystemProxies=true`

使用普通 java -jar 时同样把该开关放在 -jar 之前。不需要 PowerShell 才能访问模型。
网络参数来自 JVM 启动和进程环境，不从业务 .env 文件中晚加载为 JVM 发现开关；禁止把代理密码或 API Key 写入项目文件。

## 无 Chat 的诊断

`./dev.ps1 diagnose-proxy` 编译后启动独立 Java 诊断程序，使用与后端相同的原生发现启动开关。
不启动 Spring / HTTP 服务 / Docker，不读取 .env、Provider 凭据、业务数据或 Prompt。
诊断只进行选路、TCP、必要的代理 CONNECT 和验证证书/域名的 TLS 握手，不向 Provider 发送 HTTP 应用请求。

Maven 中验证实际 Surefire fork（显式启用后仍然零 Chat）：

`$env:P10_PROXY_DIAGNOSTIC='true'; cd ai-collab-backend; .\mvnw.cmd -B -ntp test '-Dtest=P10NetworkProxyDiagnosticTest'`

普通 IDE 或独立 Java 可直接运行 `com.shitulelv.aicollab.common.net.ProxyRouteDiagnostics`，VM option 同上。
无程序参数时仅检查默认 Gemini HTTPS 目标选路；程序参数 `--tls` 加 CONNECT/TLS 验证，也可传一个其他受 SSRF 策略允许的 HTTPS 目标。
命令行使用与应用相同的依赖 classpath；Maven Boot run 可用 main-class 覆盖运行这个独立入口，而无需启动应用服务。

诊断必须分别阅读：

1. systemDiscoveryStartupArgument / systemDiscoveryProperty：启动参数与当前属性。
2. nativeSystemProxySelected / source：本次 JDK 原生选择返回代理，不能只凭 true 推断 OS 已识别。
3. selection / proxyHost / proxyPort：当前目标实际 DIRECT 或 PROXY。
4. tcp / connect / tls / hostnameVerification：传输是否真的建立并完成验证。
5. providerReceipt：本诊断恒为 NOT_TESTED_NO_HTTP_APPLICATION_REQUEST；TLS 成功不是 Provider 接收、额度、模型或生成成功证明。

不输出 URI 路径/查询/userinfo、原始代理 URL、Proxy-Authorization、Authorization、异常原文或 Prompt。
诊断会触发目标主机的安全 DNS 校验。代理存在但不可达时失败，不改走直连，也不切换另一个代理。

## 客户端级优先级

不重写操作系统代理解析，不设置全局 ProxySelector。EnvironmentProxySelector 只安装到明确使用它的客户端。

| 顺序 | 规则 |
| --- | --- |
| 本地安全排除 | localhost、*.localhost、127.x.x.x、IPv6 loopback 和 unspecified 地址保持 DIRECT，避免本地 Ollama/MinIO 被转发 |
| 1 | JVM 显式 http(s).proxyHost/Port（以及 JDK 兼容前缀）交给 JDK 选择；其 DIRECT 排除也保持权威，不回退 ENV |
| 2 | JDK 原生选择返回代理时采用它，只保留第一个候选，不在失败后旋转或 DIRECT 回退 |
| 3 | JDK 仅返回 DIRECT 且无 JVM 显式代理时，读取 HTTPS_PROXY 或 HTTP_PROXY，再回退 ALL_PROXY；仅进程明确配置时使用 |
| 4 | ENV 回退内应用 NO_PROXY；没有可用配置则允许 DIRECT |

环境变量优先大写；大写变量不存在时才读取同名小写。所选变量为空时视为未配置，可继续回退 ALL_PROXY，不再读取同名小写。HTTPS_PROXY 表示 HTTPS 目标使用的代理，支持的代理 URL 为 **http://host[:port]**（HTTPS 目标使用 CONNECT），未给端口时为标准 HTTP 80。
不支持 https:// 代理、SOCKS 代理 URL、带 userinfo 的认证代理、path/query/fragment；拒绝非法端口，错误消息固定脱敏且不带原始异常 cause。
JVM 选择 SOCKS 时同样明确拒绝，不伪装成已支持或改走直连。

NO_PROXY 支持：逗号分隔的精确主机、.example.test / *.example.test 的根域及子域、可选端口、括号 IPv6、* 全排除。
普通裸域名只匹配自身，子域应显式使用前导点；不做含糊的字符串包含匹配。CIDR 与其他未实现语法明确拒绝，不悄悄忽略。
NO_PROXY 只影响 ENV 回退，不覆盖已选中的 JVM/OS 代理。OS 的原生排除交给 JDK，但固定本地排除优先。

**可移植边界：** JDK API 仅返回 DIRECT 时，无法区分“OS 没有配置”和“OS/PAC 主动绕过”。此时显式进程代理会作为回退生效。
如果一个非本地目标必须 DIRECT，同时又设置了 ENV 代理，应在 NO_PROXY 明确表达；不要把 OS/PAC 的 DIRECT 与存在的 ENV 代理混用后误以为二者排除语义相同。
这不是 OS/PAC 解析器，也不实现 WPAD、PAC 或注册表解析。复杂企业代理行为以 JDK 和实际诊断为准。

## 覆盖与安全

- JsonHttpModelClient 的同步 POST、SSE、GET 模型发现共用同一客户端策略，覆盖 Gemini / OpenAI-compatible / Anthropic。
- Zen 原有显式 NO_PROXY 已移除，预设端点白名单、身份头和协议不变。
- Kilo 通过共享库已有 HttpClient 构造入口复用策略，不重新实现 SSE/HTTP；停用预设仍不出站。旧 kilo.gateway.proxy-host/port 在缺少对应 HTTP(S)_PROXY 变量时补充协议候选，因此优先于通用 ALL_PROXY；不覆盖 JVM/OS/已有协议变量，也不是新增默认值。
- 旧 OpenAI Chat/Embedding 入口和系统 Embedding 使用同一 JDK 构造器；本地 Ollama 保持 DIRECT。本专项不发外部 Embedding。
- MinIO 在 SDK 默认客户端上只替换选路策略，保留默认 TLS/超时等设置，保护本地对象存储不受全局原生发现的 loopback 差异影响。
- MCP 等未接入本适配器的组件仍使用自身/JDK 原有传输；没有全局 ProxySelector 重写。

SSRF 校验仍在请求目标上执行，不能因为使用代理而跳过 HTTPS、userinfo、DNS 私网校验。
共享 JDK builder 仍是 Redirect.NEVER，证书和域名验证保持默认，不使用 trust-all、关闭主机名验证或明文降级。
配置的代理本身属于受信任网络边界；代理解析目标 DNS 的行为可能不同于本机，部署时仍需可信代理/网络 egress 控制。
每次选路固定一个候选；本适配器不新增业务重试、模型切换、代理轮转或失败后直连。业务既有预算与重试策略不因此改变。

## P10 后续批次

旧目录 `docs/acceptance-evidence/2026-10-26/p10-real-generation-01/` 原样保留。
新增真实质量批次必须指定新的 P10_REAL_BATCH_ID（例如 p10-real-generation-02）和同名大写 P10_APPROVAL，另设预算/模型/费用确认参数。
旧 01 批次已结束，门禁明确拒绝重用；每批现有 ledger 仍拒绝自动重跑，不允许删除文件绕过。
真实出站前记录实际 JsonHttpModelClient 的脱敏选路诊断。诊断通过后仍要有新批次授权和账本，不能把本专项的零 Chat 网络诊断当成质量验收。

参考：[JDK 21 网络属性](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/doc-files/net-properties.html)、[OpenJDK DefaultProxySelector](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/sun/net/spi/DefaultProxySelector.java)、[Surefire 启动参数与系统属性](https://maven.apache.org/surefire/maven-surefire-plugin/examples/system-properties.html)。
