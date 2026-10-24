# TLS 与 Refresh Cookie 安全基线（R-04）

## 1. 调用链与整改点

Cookie 全链路：`RefreshTokenCookieService`（统一构造/清除）→ `RefreshTokenProperties`（`security.refresh-token.*` 绑定校验）→ `application.yml`（默认/生产基线）与 `application-local.yml`（本地）→ `docker-compose.yml`（容器部署环境变量）→ `.env.example`（配置模板）。

| 环节 | 整改前 | 整改后 |
| --- | --- | --- |
| 默认（生产）profile | `cookie-secure: ${REFRESH_COOKIE_SECURE:true}`（默认安全，但无人强制） | 不变，且新增启动守卫（见下） |
| **docker-compose** | **硬编码 `REFRESH_COOKIE_SECURE: "false"`** —— 按此模板部署必然发放非 Secure Cookie | `${REFRESH_COOKIE_SECURE:-true}` + `REFRESH_COOKIE_ALLOW_INSECURE:-false` 透传，默认安全 |
| **启动守卫** | 无（生产误配静默降级） | `RefreshTokenProperties` 绑定校验：`cookie-secure=false` 时必须同时 `allow-insecure-cookie=true`，否则**应用拒绝启动**，报错信息给出修复指引 |
| 本地开发 | `cookie-secure: ${REFRESH_COOKIE_SECURE:false}`（受共享 .env 影响，可能被部署变量误翻转） | 固定 `cookie-secure: false` + `allow-insecure-cookie: true`，与部署侧变量彻底解耦；HTTP 开发体验不变 |
| .env.example | `REFRESH_COOKIE_SECURE=false`（模板即不安全） | `true` 为默认；纯 HTTP 容器演练需双变量显式确认，并加注释说明 |
| 客户端请求头 | 本就不读取（安全属性只来自配置） | 保持；本轮以真实请求验证 `X-Forwarded-Proto: https` 不影响 Cookie 属性 |

Refresh Token 生命周期（14d / 30d 滑动上限）、HttpOnly、SameSite=Strict、Path=/api/v1/auth 均未改变——与现有认证流程完全兼容。

## 2. 各环境最终行为

| 环境 | Secure | 依据 |
| --- | --- | --- |
| 生产（默认 profile / Compose） | **true（强制）** | yml 默认 + Compose 默认 + 绑定守卫三重保障 |
| 本地开发（local profile） | false（固定） | HTTP 开发，不受 .env 部署变量影响 |
| 纯 HTTP 容器演练 | 允许 false | 需同时显式设置两个变量，启动日志可见 |

## 3. 验证证据（真实执行，2026-10-24）

- 生产形态实例（默认 profile，8081）：登录响应 `Set-Cookie: ...; Secure; HttpOnly; SameSite=Strict`。
- 本地实例（local profile，8080）：`Set-Cookie: ...; HttpOnly; SameSite=Strict`（无 Secure，HTTP 正常）。
- 携带 `X-Forwarded-Proto: https` 请求头登录：本地 Cookie 仍无 Secure（客户端头不决定安全属性）。
- 负向：默认 profile 下 `REFRESH_COOKIE_SECURE=false` 且无 allow 标志 → `APPLICATION FAILED TO START`，提示 `cookie-secure=false 会被拒绝启动...`（exit 1）。
- 单元回归：`RefreshTokenPropertiesTest`（5 例绑定校验）+ `RefreshTokenCookieServiceTest`（5 例 Cookie 属性，含 Max-Age 只取剩余寿命、过期不产生负值、清除 Cookie 属性一致）。

## 4. 附带发现并修复的部署缺陷

验证生产实例时发现：默认 profile 的 `application.yml` **缺少 `security.jwt` 与 `security.cors` 配置块**，容器化部署（默认 profile）启动即失败（`JwtProperties` 绑定错误：secret 为空、accessTokenMinutes=0）。此前 Compose app 容器从未被真正启动过，故未暴露。本轮已补齐（`${JWT_SECRET:}`、`${JWT_ACCESS_TOKEN_MINUTES:30}`、`${AUTH_ALLOWED_ORIGINS:...}`），Compose 同步透传 `AUTH_ALLOWED_ORIGINS`。该修复使上轮审计"生产可部署"的结论首次经过真实启动验证。
