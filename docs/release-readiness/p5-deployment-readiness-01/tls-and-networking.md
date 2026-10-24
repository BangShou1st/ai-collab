# TLS、域名、CORS 与 Cookie（tls-and-networking）

> 本文件回答：TLS 在哪里终止、Cookie 如何保证安全、CORS 如何配置、N-02（forward-headers-strategy）应如何决策。

## 1. TLS 终止

- **终止位置：反向代理层**（Nginx/Caddy 或云 LB）。app 容器保持 HTTP，仅监听 8080；**不允许绕过代理直接暴露 8080**（release-runbook §0，`DOCUMENTED`）。
- 证书来源与自动续期：`ENVIRONMENT_DEPENDENT`（open-questions Q2）。
- HSTS、HTTP→HTTPS 跳转：建议在反代开启（发布日配置项）。

## 2. Refresh Cookie 安全属性（已由 R-04 整改闭环，`VERIFIED`）

| 属性 | 值 | 保证机制 |
| --- | --- | --- |
| Secure | **true（生产强制）** | yml 默认 + compose 默认（`${REFRESH_COOKIE_SECURE:-true}`）+ 启动绑定守卫三重保障：`cookie-secure=false` 且未显式 `allow-insecure-cookie=true` 时**应用拒绝启动**（RefreshTokenProperties.java:50-56） |
| HttpOnly | 是 | 代码层固定 |
| SameSite | **Strict**（正则强制只允许 Strict） | RefreshTokenProperties @DefaultValue |
| Path | `/api/v1/auth` | 固定 |
| 生命周期 | 14d，滑动上限 30d | 固定 |

关键事实（真实验证，cookie-tls-security.md）：**Cookie 安全属性只来自配置，不读取 `X-Forwarded-Proto`**——即 HTTPS 必须真实存在于用户与反代之间，由 TLS 层保证，不能靠转发头"补"出 Secure Cookie。SameSite=Strict 在同源部署（前端与 API 同域名）下正常工作；这也是推荐同源拓扑的原因之一。

## 3. CORS

- 白名单变量：`AUTH_ALLOWED_ORIGINS`（application.yml:70，默认 `http://localhost:5173` —— **默认值不可上生产**）。
- 实现约束（`VERIFIED`，CorsProperties.java:20-60）：精确 Origin，小写、无默认端口、无路径/查询、**拒绝通配符 `*`**，启动期 @AssertTrue 校验。
- 生产值：`https://<生产域名>`（同源部署下后端实际收到的请求经反代同源转发，CORS 主要约束认证端点的跨站防护，仍必须配置）。**生产值待用户提供**（open-questions Q6）。
- CSRF 补偿：认证端点强制单值 Origin/Referer 白名单（security-review.md）。

## 4. N-02：`server.forward-headers-strategy` 决策分析（本轮深入，`VERIFIED` 源码证据）

### 4.1 现状

- 全仓库**无任何** `server.forward-headers-strategy` 配置；Spring Boot 默认**不解析** `X-Forwarded-*`（保持 `NONE` 语义）。
- `AuthRateLimiter.java:28-29` 注释声称："客户端 IP 只取 `request.getRemoteAddr()`，绝不信任 X-Forwarded-For；**生产受信代理场景由 Spring ForwardedHeaderFilter 在框架层解析，限流器看到的仍是解析后地址**"。
- 但该预期**目前不成立**：ForwardedHeaderFilter 只有在 strategy 配置为 `framework` 时才注册。矛盾意味着：生产直连时注释成立；生产经反代时**注释不成立**。

### 4.2 不配置的实际影响（反代拓扑下）

| 影响点 | 后果 | 严重度 |
| --- | --- | --- |
| **注册限流**（`AuthRateLimiter.java:68`，key 只含 IP） | 所有用户共享代理 IP → 全局注册上限 10 次/小时，**新用户第 11 个起被拒** | 高，功能阻断级 |
| 登录限流（key = IP+用户名哈希） | 仍按用户名区分，单账号 30 次/小时不受影响；但同账号暴力尝试无法按真实来源 IP 区分 | 中 |
| 审计日志 `RemoteAddr` | 全部记为代理内网 IP，溯源能力下降 | 中 |
| 绝对 URL / 重定向 | 当前 API 均相对路径（前端 `/api/v1`），暂未发现受影响点 | 低 |

### 4.3 决策流程（不得盲目设置）

前提确认（全部满足才允许 `framework`）：
1. 反代是**唯一受信入口**，且**总是**设置/覆盖（而非透传客户端可伪造的）`X-Forwarded-For`、`X-Forwarded-Proto`、`X-Forwarded-Host`；外部无法直连 app 8080。
2. 反代配置本身已固定（nginx 示例：`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_set_header X-Forwarded-Proto $scheme;`）。
3. 部署日验证通过（见 4.4）。

满足后最小变更：compose app `environment` 增加 `SERVER_FORWARD_HEADERS_STRATEGY: framework`（无需改代码）。若拓扑是"云 LB + 不设 X-Forwarded-For"或"多级不可信代理"，则**不配置**，并接受 4.2 中注册限流全局化的替代缓解（例如临时调高 `auth.rate-limit.register-per-hour` 或按用户名维度补充限流——后者属代码变更，需另行评审）。

### 4.4 部署日验证方法

```bash
# 1) 经反代登录，抓 Set-Cookie（应与直连一致：Secure; HttpOnly; SameSite=Strict）
curl -is https://<域名>/api/v1/auth/<login-endpoint> -H 'Content-Type: application/json' -d '<测试凭据>' | grep -i set-cookie
# 2) 伪造头不生效：带假 X-Forwarded-For 从公网直连 8080 应不可达（防火墙验证）
# 3) 注册限流按真实 IP 生效：模拟两个来源 IP 注册互不影响计数
```

**状态结论：`ENVIRONMENT_DEPENDENT`。** 在代理拓扑确认前不修改任何配置；此项必须在首次部署前形成明确决策（做或不做、依据是什么），不得保持"未定义"状态上线。

## 5. 域名与 DNS

- 生产域名、DNS 记录：`ENVIRONMENT_DEPENDENT`（open-questions Q1）。
- 同源部署只需一个主域名（前端静态 + `/api/v1` 同域），无需子域拆分。
