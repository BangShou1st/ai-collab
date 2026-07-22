# Refresh Token 多设备会话设计

## 目标与范围

在现有用户名密码登录、JWT Access Token 和 `/api/v1/auth/me` 基础上，增加生产可用的多设备 Refresh Token 会话闭环。本阶段只实现登录、刷新、当前设备登出、轮换重用检测、滑动/绝对过期、Cookie/CORS/来源安全、测试和文档；不实现项目、成员、RBAC、前端、MinIO、RAG 或 AI 功能。

## 固定安全模型

- Access Token 保持 HS256 JWT，默认 30 分钟，仅通过 JSON 返回，业务请求使用 Bearer Header。
- Refresh Token 是 `SecureRandom` 生成的至少 32 字节随机值，经 URL-safe Base64 无填充编码；浏览器仅通过 HttpOnly Cookie 持有原文。
- 数据库只保存 Refresh Token 的 SHA-256 小写十六进制哈希，不保存、记录或返回原文。
- 每次登录创建独立 `session_id`；同一设备的轮换链共享该 ID，不同设备互不影响。
- 单个 Refresh Token 从签发起最多 14 天；每次轮换重新获得最多 14 天，但不得超过登录起 30 天的 `session_expires_at`。
- 刷新严格一次性使用：旧 Token 标记 `ROTATED` 并指向新 Token。再次使用已轮换 Token，撤销该 `session_id` 下所有仍有效 Token，原因记为 `REUSE_DETECTED`，对外统一返回 401。
- 当前设备登出按 Cookie 定位会话并撤销该会话，原因记为 `LOGOUT`；缺失、格式错误、未知、过期或已撤销 Token 等凭据状态错误均幂等成功。数据库等基础设施失败保留 500，但响应仍清除 Cookie。Access Token 不进黑名单，等待自然过期。

## 数据模型与迁移

不修改已执行的 V1。V2 在现有 `refresh_token(id, user_id, token_hash, expires_at, revoked_at, created_at)` 上新增：

- `session_id uuid`
- `session_expires_at timestamptz`
- `revoke_reason varchar(32)`
- `replaced_by_token_id uuid`，自引用并在删除时 `SET NULL`

迁移先增加可空列，为旧记录逐行生成 `session_id`，以原有 `expires_at` 回填 `session_expires_at`，再设置非空。`revoke_reason` 只允许 `ROTATED/LOGOUT/REUSE_DETECTED/EXPIRED/USER_UNAVAILABLE`。保留现有索引并补充 `session_id`、`(session_id, revoked_at)`；`token_hash` 既有唯一约束已可作为查询索引。

## 组件边界

- `AuthController`：HTTP、Cookie 输入输出和 DTO，不承载会话规则。
- `AuthService`：编排登录、刷新、登出和 Access Token 签发。
- `RefreshTokenService`：会话创建、轮换、重用检测和撤销；`rotate` 以 `REQUIRED` 加入 `AuthService.refresh` 的外层事务。
- `RefreshTokenRepositoryService/Mapper`：数据库读写和明确的 PostgreSQL `FOR UPDATE` 查询。
- `RefreshTokenEntity`：纯数据库映射，不含原始 Token 字段。
- `RefreshTokenGenerator/HashService`：分别负责随机凭据与 SHA-256。
- `RefreshTokenCookieService`：生成、轮换和清除 Cookie；业务 Service 不依赖 Servlet 响应。
- `AuthRequestOriginValidator` 与 `CorsProperties`：精确来源校验和 CORS allowlist。

## 核心调用链

登录：`AuthController.login → OriginValidator → AuthService.login → RefreshTokenService.createSession → Repository/Mapper → CookieService`。

刷新：`AuthController.refresh → OriginValidator → AuthService.refresh → RefreshTokenService.rotate → Mapper.selectForUpdate → AccessTokenService → CookieService`。

登出：`AuthController.logout → OriginValidator → AuthService.logout → RefreshTokenService.revokeSession → CookieService.clear`。

`AuthService.refresh` 的外层事务执行 `SELECT ... FOR UPDATE → 校验 → 创建新记录 → 旧记录 ROTATED/replaced_by_token_id → Access Token 签发 → 提交`；签发发生技术异常时整体回滚。第二个并发请求在获得锁后看到旧 Token 已轮换，撤销该会话的新 Token 并返回 401；该业务异常不回滚安全撤销。

## HTTP 与错误契约

- `POST /api/v1/auth/login`：响应保持 Access Token 和用户信息，并设置 Refresh Cookie。
- `POST /api/v1/auth/refresh`：无需 Access Token，只读 Cookie，响应仅含新 Access Token 并轮换 Cookie。
- `POST /api/v1/auth/logout`：无需 Access Token；凭据状态错误幂等成功，数据库等基础设施失败返回 500，但两类响应都清除 Cookie。
- `/refresh` 的任何认证失败统一为 `401 AUTH_UNAUTHORIZED / 当前登录状态无效，请重新登录`。
- 不可信 Origin/Referer 统一为 `403 AUTH_FORBIDDEN`，不泄露 allowlist。

## Cookie、CORS 与 CSRF

Cookie 固定名 `ai_collab_refresh_token`，`HttpOnly=true`、`SameSite=Strict`、`Path=/api/v1/auth`，local `Secure=false`、生产 `true`，不设置 Domain，Max-Age 不超过实际剩余寿命。清除时复用同名、Path、SameSite、Secure 并设置 Max-Age=0。

CORS 使用精确来源列表、`allowCredentials=true`，允许 GET/POST/OPTIONS 与 Authorization/Content-Type/Cache-Control，绝不使用 `*`。登录、刷新、登出优先精确校验 Origin，缺失时精确校验 Referer 的 origin。默认 CSRF 可继续关闭，但 Cookie 端点由 HttpOnly、SameSite、生产 Secure、来源校验和 CORS allowlist 共同保护；CORS 本身不是 CSRF 防护。

## 配置与验证

`security.refresh-token` 绑定 14/30 天、Cookie 名称/Path/SameSite/Secure；`security.cors.allowed-origins` 绑定 `AUTH_ALLOWED_ORIGINS`，默认 `http://localhost:5173`。

测试按 Red-Green-Refactor 覆盖迁移、生成/哈希、Cookie、登录、刷新、重用、登出、CORS/来源、JWT 保护和真实 PostgreSQL 并发。最终执行 `mvnw test`、`mvnw verify`、Flyway/表结构查询、健康检查和手工接口烟测；任何未验证项必须明确报告。
