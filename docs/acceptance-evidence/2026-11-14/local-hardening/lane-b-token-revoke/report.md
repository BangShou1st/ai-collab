# Lane B / H-2 Token 撤销

## 结论词

| 项 | 状态 |
| --- | --- |
| Access Token `tv` claim + 请求级状态校验 | IMPLEMENTED |
| 停用/改密/重置/logoutAll 统一会话撤销 | IMPLEMENTED |
| Refresh 换发校验用户状态与会话版本 | IMPLEMENTED |
| Access Token 默认 TTL 30→15 分钟 | IMPLEMENTED |
| 单元测试 46 项 | TESTED(UNIT) |
| 真实 HTTP + PostgreSQL/Redis/MinIO 集成测试 7 项 | TESTED(INTEGRATION) |
| 独立 OS 进程重启验收 | NOT_RUN |
| 用户删除调用链 | NOT_RUN：产品当前没有用户删除用例/端点 |
| 全量 `mvnw verify` | NOT_RUN：留给中心 Wave 1 门禁 |

本 lane 不写 `PASS` / `FAIL` / “验收通过”；最终判定由中心复核。

## 实现

- `AccessTokenService` 写入 `tv`；缺失按 0 编码，兼容旧格式。
- `AuthSessionGuardFilter` 挂在 `BearerTokenAuthenticationFilter` 之后，仅处理 `JwtAuthenticationToken`；
  用户不存在、状态非 `ACTIVE` 或版本不一致时清空 SecurityContext 并返回
  `401 AUTH_SESSION_REVOKED`。
- `UserSessionStateService` 使用 Caffeine，`expireAfterWrite=30s`、`maximumSize=10_000`；
  统一撤销时即时 evict，并在事务提交后再次 evict。
- `UserSessionRevocationService` 统一递增 `token_version`（或接收已递增状态）、撤销全部 Refresh Token、
  清缓存并写 `USER_SESSIONS_REVOKED` 审计。调用点：停用、启用、改密、密码重置、`logoutAll`。
- `RefreshTokenGenerator` 在 43 字符不透明令牌中嵌入会话版本；无标记的旧随机令牌按版本 0 兼容。
  `RefreshTokenService` 换发前比对当前用户版本，不一致或非 ACTIVE 时撤销该会话并 401。
- 仓库默认 `JWT_ACCESS_TOKEN_MINUTES=15`；当前本机 `.env` 原有显式值 `30` 未改动，
  本地 demo 若需 15 分钟需由中心决定是否调整该本地配置。

## 失败与边界矩阵

| 场景 | 断言 | 证据 |
| --- | --- | --- |
| 停用后旧 Access | 401 `AUTH_SESSION_REVOKED` | `integration-tests.txt` / `TokenRevocationIntegrationTest` |
| 停用后旧 Refresh | 401，不签发新 Token | 同上 |
| 改密后另一设备旧 Access | 401 | 同上 |
| `logoutAll` | 旧 Access 401、旧 Refresh 401 | 同上 |
| 重新启用 | 旧 Access/Refresh 仍 401，新签发 Token 可用 | 同上 |
| 旧格式无 `tv` Access | `token_version=0` 可用；版本递增后 401 | 同上 |
| 版本不一致 Refresh | 撤销该 session 并 401 | `unit-tests.txt` / `RefreshTokenServiceTest` |
| 状态缓存 | 30 秒内重复请求只查询一次 DB，evict 后重查 | `unit-tests.txt` / `UserSessionStateServiceTest` |
| 畸形/短 Refresh 字符串 | 统一 401，不进入数据库查找 | `unit-tests.txt` |

## 原始证据

- `raw/compile.txt` / `raw/compile.exit.txt`：主源码编译，`exit=0`
- `raw/unit-tests.txt` / `raw/unit-tests.exit.txt`：46 tests，0 failures/errors/skips，`exit=0`
- `raw/integration-tests.txt` / `raw/integration-tests.exit.txt`：7 tests，0 failures/errors/skips，`exit=0`
- `raw/defaults.txt`：TTL 与六开关/gateway 默认值
- `raw/migrations.txt`：仍为 V1–V99，本 lane 未新增迁移
- `raw/status-DIFF.txt`：提交前 diff 与 `git diff --check`
- `raw/commit.txt`：实现提交摘要

## 说明

- 集成测试使用 Testcontainers PostgreSQL 17、Redis 7、MinIO 和随机端口真实 HTTP；未在独立 OS 进程中重启。
- Testcontainers 关闭上下文时出现 Redis watchdog / Hikari 连接关闭警告，测试汇总仍为
  `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`；未把 teardown 噪声当业务结果。
- 无新迁移；未改历史迁移；未改 `main`；未 push；未启用 MCP Gateway 或生产默认路径。
