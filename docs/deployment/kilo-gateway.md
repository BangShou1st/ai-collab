# Kilo Gateway Free 供应商部署说明

本文档说明 AI Collab 接入的第二个模型供应商 **Kilo Gateway Free**（共享库 `BangShou1st/kilo-gateway` v0.2.1）。
内容以当前代码为准：预设注册表 `ProviderPresetRegistry`、传输层 `KiloGatewayClientTransport`、
执行层 `KiloGatewayModelExecution`、预设服务 `KiloGatewayPresetService` 与迁移
`V65__add_kilo_gateway_preset.sql`。

## 1. 与 OpenCode Zen 的关系

- Kilo 与 Zen 是两个**互相独立**的产品预设（`ProviderPresetCode.KILO_GATEWAY_FREE` / `OPENCODE_ZEN_FREE`）。
  wire 协议同为 `OPENAI_COMPATIBLE`，但配置、凭证与用途绑定各自独立。
- 每个用户对每个预设最多一行 `user_ai_provider`（唯一索引 `uk_user_ai_provider_user_preset`），
  两个预设可**同时连接**，互不覆盖。
- 断开其中一个只删除该预设行：另一个预设的配置与用途绑定不受影响。
  被删除预设的用途绑定随外键级联清空，且**不会自动回填默认值**给其他预设。
- Kilo 预设**不保存任何凭证**（`encrypted_api_key` 为 NULL），也绝不从 Zen 或其他 Provider 继承密钥。

## 2. 依赖与网络要求

- Maven 坐标（JitPack 多模块格式 `com.github.User.Repo:Module:Tag`）：

  ```
  com.github.BangShou1st.kilo-gateway:kilo-gateway-spring-boot-starter:v0.2.3
  ```

  版本集中在 `ai-collab-backend/pom.xml` 的 `kilo-gateway.version`（当前 `v0.2.3`）。
  该版本在 `streamChat()` 中要求上游终止哨兵 `data: [DONE]`，见 §6。
- **构建期**需要能访问 `https://jitpack.io`（按 Git tag 构建并托管构件，公开仓库无需 Token）。
  首次解析某 tag 时 JitPack 需现场构建，可能耗时数分钟；也可能因上游限流（例如 Maven Central 429）暂时失败。
  失败时可重试，或用 `--refresh-dependencies` / `-U` 强制刷新；容器镜像构建前建议预热本地或 CI 依赖缓存。
- **运行期**需要能访问 `https://ai-gateway.kilo.ai`（HTTPS）。

## 3. 配置项（前缀 `kilo.gateway`）

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 关闭后容器不再注册 `KiloGatewayClient`；本项目的 Kilo 组件仍可启动，只在真正使用预设时报“Kilo Gateway 未启用”，不会因缺依赖导致应用启动失败 |
| `base-url` | `https://ai-gateway.kilo.ai/api/v1` | 仅允许 HTTPS（`http` 只允许 loopback，用于本地测试）；不接受客户端输入 |
| `model` | `poolside/laguna-s-2.1:free` | 仅作为库内缺省；本项目一律以用户在界面上选择的模型出站，不依赖此缺省 |
| `free-only` | `true` | 免费准入开关；**生产必须保持 `true`** |
| `fallback-models` | 空 | 仅当主模型返回 500/502/503/504 时才依次切换；默认不配置 |
| `connect-timeout` | `10s` | 连接超时 |
| `request-timeout` | `90s` | 单次请求超时 |
| `proxy-host` / `proxy-port` | 空 / `0` | 出站代理；只能由部署配置指定，不接受客户端输入 |

上述属性可用 Spring 标准 relaxed binding 以环境变量覆盖（例如 `KILO_GATEWAY_BASE_URL`、
`KILO_GATEWAY_FREE_ONLY`、`KILO_GATEWAY_REQUEST_TIMEOUT`）。**未在仓库中设置任何 `kilo.gateway.*`，
即启用共享库默认值。**

## 4. 生产默认建议

- **保持匿名 + free-only**：不要设置 Kilo API Key，也不要把 `free-only` 关掉。
  未配置 Key 时共享库不会发送 `Authorization` 头；一旦配置 Key，请求就不再匿名。
- **默认不配置 `fallback-models`**。只有逐个在目标环境验证过可用性后才启用，避免用切换模型
  掩盖限流或故障。
- **不要为 Kilo 配置代理**，除非确有出口代理需求（该代理只影响 Kilo 出站）。
- 不要把 `:free` 后缀理解为永久免费保证，见 §6。

## 5. 用户侧操作

在「个人设置 → AI 设置」的「推荐连接」区：

1. **Kilo Gateway 卡片**：选择免费模型（可用刷新按钮拉取当前网关免费目录）→「测试连接」→「保存」。
   可勾选「设为默认模型」。
2. **用途覆盖**：在「按用途覆盖默认模型」中把知识问答 / AI 规划 / Agent 分别指向
   `Kilo Gateway Free · <模型>`。
3. **断开连接**：删除本人 Kilo 预设行（不涉及任何凭证）；用途覆盖回退，Zen 连接保持不变。

## 6. 免费模型、限流与流式完成契约

- 免费识别由共享库 `FreeModelPolicy` 决定：模型 id 以 `:free` 结尾，或等于 `kilo-auto/free`。
  这是**形状判定，不是实时价格或额度认证**。
- 本项目的准入是「共享库免费判定 ∩ 网关实时免费目录」：保存与测试都要求模型当前仍在免费目录内，
  否则报校验错误（`:free` 后缀本身不构成准入证据）。目录变化时请在界面刷新模型后重新选择。
- 模型可用性与价格可能随时变化，`:free` 后缀**不构成永久免费保证**。
- **流式完成契约（v0.2.3 起）**：SSE 流只有在观察到 `data: [DONE]` 时才算成功。
  提前 EOF 而无该哨兵、中途出现错误帧、或帧无法解析，一律判为失败；失败流不重放已输出内容，
  也不会切换 fallback（未完成流不是可重试的上游错误）。应用层因此只在正常结束时回调 onDone。

## 7. 故障区分

| 现象 | 错误码 / 提示 | 处置 |
| --- | --- | --- |
| 构建期无法解析 `...:v0.2.3` | Maven 报 JitPack 解析失败 | 网络或 JitPack 构建失败；查看 https://jitpack.io 的构建日志，必要时重新出包 |
| `429 Too Many Requests` | `AI_PROVIDER_QUOTA_EXCEEDED` 模型服务额度不足 | 共享库**不重试、不切 fallback**；稍后再试 |
| 网关 5xx（500/502/503/504） | `AI_PROVIDER_ERROR` | 仅在配置了 `fallback-models` 时才会切换模型 |
| 401 / 403 | `AI_MODEL_CREDENTIAL_INVALID` | 匿名免费路径通常不会出现；检查是否配置了无效 Key |
| 超时（根因 `HttpTimeoutException`） | `AI_MODEL_TIMEOUT` | 检查网络与 `request-timeout` |
| 连接失败 / SSE 中断 / 非法 JSON / 中途错误帧 | `AI_PROVIDER_ERROR` | 共享库同样报告负 statusCode，但不会被伪装成超时 |
| 流未完成（缺少 `[DONE]`） | `AI_PROVIDER_INVALID_RESPONSE` 模型服务返回了无效响应 | 输出按不完整丢弃；稍后重发整轮请求 |
| 模型不在当前免费目录 | 校验错误「不是当前 Kilo 免费模型」 | 刷新模型后重新选择 |
| 预设已停用（`enabled=false`） | `AI_PROVIDER_UNAVAILABLE` 模型服务尚未配置或暂时不可用 | 同步与流式都会在出站前拒绝，不会发出模型请求 |
| 共享库被关闭（`kilo.gateway.enabled=false`） | `AI_PROVIDER_UNAVAILABLE`「Kilo Gateway 未启用」 | 恢复 `kilo.gateway.enabled=true` |

## 8. 安全约定

- 本预设不读取、不保存任何凭证，不会把 Kilo 请求关联到用户或 Zen 的密钥。
- 上游地址、路径、代理与超时均为**服务端拥有**：保存接口只接受模型名、启用与默认标记，
  用户无法通过请求参数提交 base URL、路径、代理或解除免费限制。
- 共享库客户端固定 `Redirect.NEVER`，不做自动跳转；base URL 必须是 HTTPS。
- **上游原始响应正文（HTML、内部路径、上游错误细节）只写内部日志**，客户端只会收到
  `ErrorCode` 的固定提示，不携带上游消息、堆栈或凭据。
- 维护本文档时**不得写入**真实 Token、内部环境变量取值、私有代理地址或任何凭据。
