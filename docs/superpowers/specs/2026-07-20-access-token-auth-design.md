# Access Token 认证闭环设计

## 范围

本阶段只实现登录、查询当前用户与匿名健康检查。认证使用短期 HMAC-SHA256 JWT，不实现 Refresh Token、注销、注册、RBAC 或其他业务模块。

## 架构

- `auth` 模块负责 HTTP DTO、认证用例与 Access Token 签发。
- `user` 模块负责 `app_user` 映射和用户查询、更新。
- `common.api`、`common.exception` 提供统一响应和错误映射。
- `common.security` 配置 Spring Security Resource Server、JWT 编解码和 401/403 JSON 响应。
- Controller 不访问 Mapper，Entity 不作为 API 响应。

## 数据流

登录请求经 Jakarta Validation 后进入 `AuthService`。服务通过 `UserService` 查询用户，使用 BCrypt 校验密码，检查状态，更新 `last_login_at`，再由 `AccessTokenService` 签发 JWT。`/auth/me` 的 Bearer Token 由 Resource Server 验签和校验过期时间，Controller 从 `Jwt` 读取 `sub`，再查询数据库确认账号仍存在且启用。

## 错误契约

- 不存在用户或密码错误：401 / `AUTH_INVALID_CREDENTIALS`。
- 登录时账号禁用：403 / `USER_DISABLED`。
- `/auth/me` 对应账号不存在或禁用：401 / `AUTH_UNAUTHORIZED`。
- 无 Token 或无效 Token：401 / `AUTH_UNAUTHORIZED`。
- 已认证但权限不足：403 / `AUTH_FORBIDDEN`。

## 测试策略

使用 MockMvc 覆盖 HTTP 契约，使用 Mockito 单元测试覆盖认证分支，使用 Testcontainers + `pgvector/pgvector:pg17` 覆盖真实迁移与 Mapper，并单测 local 初始化器幂等性。每个行为遵循先红后绿，最后执行 Maven `test` 与 `verify`。

## 配置与密钥

`application-local.yml` 仅通过环境变量读取 `JWT_SECRET`、`JWT_ACCESS_TOKEN_MINUTES`、`DEMO_OWNER_USERNAME` 和 `DEMO_OWNER_PASSWORD`。源码、日志和 Token 均不包含密码或密钥。
