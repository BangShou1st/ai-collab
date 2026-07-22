# Access Token Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 Spring Boot 模块化单体中实现可测试的登录、JWT 当前用户查询和匿名健康检查闭环。

**Architecture:** 认证编排、用户持久化与安全基础设施分离；Spring Security Resource Server 负责 Bearer Token 验证，业务服务在验证后重新读取数据库用户状态。统一响应和异常处理保证 HTTP 状态与业务码一致。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring Security 7.1.0、Spring MVC、MyBatis-Plus 3.5.17、PostgreSQL 17、Flyway、JUnit 5、MockMvc、Testcontainers。

## Global Constraints

- 不修改 Spring Boot 4.1.0、Java 21、MyBatis-Plus 3.5.17 的版本。
- 不修改 `V1__init_schema.sql`、Docker Compose 或 `.env`。
- 不引入 JPA、WebFlux、Lombok，不实现 Refresh Token 或范围外模块。
- 所有生产行为先有失败测试；核心类和关键设计使用有学习价值的中文注释。

---

### Task 1: 测试基础与统一 API 契约

**Files:**
- Modify: `ai-collab-backend/pom.xml`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/TestcontainersConfiguration.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/api/ApiResponse.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/BusinessException.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/GlobalExceptionHandler.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/common/exception/GlobalExceptionHandlerTest.java`

**Interfaces:** Produces `ApiResponse<T>`, `ErrorCode`, `BusinessException` and MVC exception translation used by later tasks.

- [ ] 写验证错误和业务异常的 MockMvc 失败测试并确认 RED。
- [ ] 增加 OAuth2 Resource Server 依赖，修正 pgvector 测试容器镜像。
- [ ] 实现统一响应与异常处理，运行定向测试确认 GREEN。

### Task 2: 用户持久化与认证服务

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/model/UserStatus.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/entity/UserEntity.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/mapper/UserMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/service/UserService.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/dto/LoginRequest.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/dto/LoginResponse.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/dto/CurrentUserResponse.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/service/AuthService.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/service/AccessTokenService.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/auth/service/AuthServiceTest.java`

**Interfaces:** `UserService` 查询/更新用户；`AuthService.login(LoginRequest)` 返回 `LoginResponse`；`AccessTokenService.createToken(UserEntity)` 签发 Token。

- [ ] 写成功、错误密码、不存在用户、禁用用户与登录时间更新测试并确认 RED。
- [ ] 实现 Entity、枚举、Mapper、DTO、用户服务与认证编排。
- [ ] 实现 BCrypt 校验和 Token 服务，运行定向测试确认 GREEN。

### Task 3: JWT 与 HTTP 安全闭环

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/JwtProperties.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/JwtConfiguration.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/RestAuthenticationEntryPoint.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/RestAccessDeniedHandler.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/security/SecurityConfig.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/auth/controller/AuthController.java`
- Modify: `ai-collab-backend/src/main/resources/application-local.yml`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/auth/controller/AuthControllerTest.java`

**Interfaces:** `POST /api/v1/auth/login`、`GET /api/v1/auth/me`；`JwtProperties` 绑定 `security.jwt.*`；Security Filter Chain 仅放行登录和健康检查。

- [ ] 写十项 API/安全契约失败测试并确认 RED。
- [ ] 配置 JwtEncoder/JwtDecoder、无状态 Resource Server、401/403 处理器与 Controller。
- [ ] 运行 API 测试确认 GREEN，并保持其他路径默认认证。

### Task 4: local 初始化器与学习文档

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/user/config/LocalDemoUserInitializer.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/user/config/LocalDemoUserInitializerTest.java`
- Create: `docs/learning/phase-01-access-token-auth.md`

**Interfaces:** local profile 启动时按用户名幂等创建 BCrypt 用户；非 local 不注册该组件。

- [ ] 写重复执行只插入一次的失败测试并确认 RED。
- [ ] 实现初始化器并确认 GREEN。
- [ ] 编写包含两张 Mermaid 时序图、核心概念、排错、阅读与断点顺序的中文文档。

### Task 5: 全量验证与真实接口检查

**Files:** No production changes unless a verified defect requires a new failing regression test.

- [ ] 运行 `.\\mvnw.cmd test`，读取完整结果。
- [ ] 运行 `.\\mvnw.cmd verify`，读取完整结果。
- [ ] 使用 local profile 与现有 Docker 基础设施启动应用。
- [ ] 验证健康检查、成功/失败登录、无 Token/有效 Token `/auth/me`，并检查启动日志。
