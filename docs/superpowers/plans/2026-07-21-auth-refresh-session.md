# Refresh Token Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and execute the tasks in order. Each production behavior must first be demonstrated by a failing test.

**Goal:** 为现有认证模块增加多设备 Refresh Token 会话、严格轮换/重用检测、当前设备登出和 Cookie 来源安全。

**Architecture:** Controller 只处理 HTTP/Cookie，AuthService 编排用例，RefreshTokenService 持有事务与会话规则，Repository/Mapper 执行持久化和行锁。原始 Token 仅存在于请求 Cookie 和短生命周期值对象中，数据库只保存 SHA-256。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring Security Resource Server、MyBatis-Plus 3.5.17、PostgreSQL 17、Flyway、JUnit 5、MockMvc、Testcontainers。

## Global Constraints

- 不修改 `V1__init_schema.sql`，只新增 V2。
- 不升级或降级 Java、Spring Boot、MyBatis-Plus；不引入 JPA、WebFlux、Lombok。
- 不读取或输出密码、JWT Secret、Refresh Token 原文或完整 token_hash；不修改根 `.env`。
- Refresh Token 32 字节 SecureRandom、URL-safe Base64 无填充；哈希为 SHA-256 小写 64 十六进制字符。
- 滑动有效期 14 天，Session 绝对有效期 30 天；严格一次性轮换，无重用宽限窗口。
- 核心类和关键原因使用准确的中文学习型注释。

### Task 1: V2 可迁移数据库模型

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V2__extend_refresh_token_session.sql`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/auth/refresh/RefreshTokenMigrationIntegrationTest.java`

- [ ] 写 Testcontainers 测试，通过 `information_schema`/`pg_indexes`/`pg_constraint` 断言新增列、非空、回填、检查约束、自引用外键和索引。
- [ ] 运行 `mvnw -Dtest=RefreshTokenMigrationIntegrationTest test`，确认因 V2 缺失失败。
- [ ] 编写向后安全的 V2：可空新增、旧数据回填、设非空、约束和索引。
- [ ] 重跑目标测试和全量测试。

### Task 2: 配置、生成、哈希和 Cookie 基础设施

**Files:**
- Create: `common/security/RefreshTokenProperties.java`, `common/security/CorsProperties.java`
- Create: `auth/service/RefreshTokenGenerator.java`, `RefreshTokenHashService.java`, `RefreshTokenCookieService.java`
- Create tests with matching names under `src/test/java`.
- Modify: `application-local.yml`, `JwtConfiguration.java` or application configuration registration.

- [ ] 先测试属性绑定、随机性/长度/URL-safe、哈希确定性/格式、Cookie 属性和清除属性。
- [ ] 分组运行测试确认缺少类型/行为而失败。
- [ ] 实现最小生产类；Cookie API 使用 Spring `ResponseCookie`，Max-Age 由实际 expiresAt 和 Clock 计算。
- [ ] 重跑目标测试和全量测试。

### Task 3: Entity、Mapper 与仓储服务

**Files:**
- Create: `auth/refresh/entity/RefreshTokenEntity.java`
- Create: `auth/refresh/model/RefreshTokenRevokeReason.java`
- Create: `auth/refresh/mapper/RefreshTokenMapper.java`
- Create: `auth/refresh/service/RefreshTokenRepositoryService.java`
- Create: mapper/repository integration tests.

- [ ] 先测试 UUID/OffsetDateTime/枚举映射、哈希查询、`FOR UPDATE` 查询和按 session 撤销。
- [ ] 确认测试因映射/SQL 缺失失败。
- [ ] 实现无原文属性的 Entity、显式 `SELECT ... FOR UPDATE`、插入/轮换/撤销 SQL 和薄仓储门面。
- [ ] 重跑目标测试和全量测试。

### Task 4: 登录创建多设备会话

**Files:**
- Create: `auth/model/IssuedRefreshToken.java`, `AuthenticationResult.java`
- Create: `auth/service/RefreshTokenService.java`
- Modify: `AuthService.java`, `AuthController.java`
- Modify/add login unit and integration tests.

- [ ] 测试登录写入 HttpOnly Cookie、JSON 不泄露 Refresh Token、DB 仅有哈希、两次登录 session 不同且互不影响。
- [ ] 运行目标测试并确认新断言失败。
- [ ] 实现 `createSession`，AuthService 返回 `AuthenticationResult`，Controller 写 Cookie。
- [ ] 重跑登录测试和全量测试。

### Task 5: 刷新轮换、过期和重用检测

**Files:**
- Create: `auth/dto/AccessTokenResponse.java`, `auth/model/RefreshResult.java`
- Modify: `RefreshTokenService.java`, `AuthService.java`, `AuthController.java`
- Create/modify refresh integration tests.

- [ ] 先测试有效轮换、同 session、新 expiresAt 上限、绝对期限不变、缺 Cookie/随机/过期/session 过期/用户不可用统一 401。
- [ ] 运行目标测试并确认路由或行为缺失导致失败。
- [ ] 在一个 `@Transactional` 方法中行锁旧记录，校验，创建新记录，旧记录标记 ROTATED 并关联替代记录，再签发 Access Token。
- [ ] 增加已 ROTATED Token 重用测试，断言仅当前 session 被 `REUSE_DETECTED` 撤销。
- [ ] 实现重用处理并重跑目标测试和全量测试。

### Task 6: 当前设备幂等登出

**Files:**
- Modify: `RefreshTokenService.java`, `AuthService.java`, `AuthController.java`
- Create/modify logout integration tests.

- [ ] 先测试有效登出、缺 Cookie、随机 Token、已撤销 Token、重复登出、其他 session 不受影响、旧 Token 不能刷新和清除 Cookie。
- [ ] 确认新测试失败。
- [ ] 实现按 Token 哈希定位 session、撤销有效链并始终返回成功；Controller 总是清除 Cookie。
- [ ] 重跑登出测试和全量测试。

### Task 7: CORS 与 Origin/Referer 校验

**Files:**
- Create: `common/security/AuthRequestOriginValidator.java`
- Modify: `common/security/SecurityConfig.java`, `application-local.yml`
- Create/modify security integration tests.

- [ ] 先测试 allowlist CORS、credentials、无通配符、OPTIONS、refresh/logout 匿名、me 受保护，以及 Origin/Referer 精确匹配和 403。
- [ ] 确认测试因当前安全配置失败。
- [ ] 实现 `CorsConfigurationSource` 和独立来源校验器，在三个 Cookie 端点调用；更新 CSRF 安全模型注释。
- [ ] 重跑安全测试和全量测试。

### Task 8: 真实 PostgreSQL 并发验证

**Files:**
- Create: `auth/RefreshTokenConcurrencyIntegrationTest.java`

- [ ] 使用 `ExecutorService` 和 `CyclicBarrier/CountDownLatch` 同时提交同一 Refresh Token，禁止以 `Thread.sleep` 保证顺序。
- [ ] 先运行并确认在并发实现未完成或不正确时失败。
- [ ] 调整事务/锁定边界，使一个请求先轮换，另一个检测重用并撤销当前 session；断言其他 session 有效。
- [ ] 重复运行并发测试后执行全量测试。

### Task 9: Postman 与中文学习文档

**Files:**
- Create: `docs/postman/AI-Collab-Auth.postman_collection.json`
- Create: `docs/postman/AI-Collab-Local.postman_environment.json`
- Create: `docs/postman/README.md`
- Create: `docs/learning/phase-02-refresh-token-session.md`

- [ ] 创建 12 个指定请求，使用 Cookie Jar，脚本只保存 `data.accessToken`，Cookie 请求统一添加 Origin。
- [ ] 校验两个 JSON 可解析且环境变量只含 `baseUrl/frontendOrigin/accessToken`。
- [ ] 编写中文导入/轮换/多设备测试说明和含五幅 Mermaid 图的学习文档，覆盖附件规定的 28 个主题。

### Task 10: 完整验证和变更审查

- [ ] 运行 `ai-collab-backend\\mvnw.cmd test`，记录测试数、失败数和退出码。
- [ ] 运行 `ai-collab-backend\\mvnw.cmd verify`，记录退出码。
- [ ] 用本地 Docker Compose 验证 Flyway V2、`\\d refresh_token`、健康检查和登录/刷新/登出 Cookie；查询会话时不选择 token_hash。
- [ ] 审查所有新增类职责、敏感日志、JSON 泄漏、配置默认值和附件验收清单；诚实记录未验证项。

