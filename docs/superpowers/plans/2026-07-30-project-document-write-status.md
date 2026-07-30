# Project Document Write Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 允许 PREPARING/ACTIVE 项目修改文档，并让 COMPLETED/ARCHIVED 项目返回明确的只读错误。

**Architecture:** 项目领域策略定义唯一可写状态集合；项目仓储在注册事务中锁定并返回真实状态；所有文档写入口在业务服务层复用状态校验。读取入口保持现状。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、PostgreSQL、JUnit 5、Testcontainers

## Global Constraints

- `PREPARING`、`ACTIVE` 可写；`COMPLETED`、`ARCHIVED` 只读。
- `PROJECT_NOT_FOUND` 只表达不存在或成员不可见。
- 只读写操作返回 HTTP 409 和 `PROJECT_READ_ONLY`。
- MinIO 网络调用不能进入数据库长事务。
- 项目子资源必须按 `projectId` 隔离。

---

### Task 1: 项目可写状态策略

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/domain/policy/ProjectWritePolicy.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/domain/policy/ProjectWritePolicyTest.java`

**Interfaces:**
- Consumes: `ProjectStatus`
- Produces: `ProjectWritePolicy.requireWritable(ProjectStatus status): void`

- [ ] **Step 1: 写失败测试**

测试 `PREPARING/ACTIVE` 不抛异常，`COMPLETED/ARCHIVED` 抛出 code 为 `PROJECT_READ_ONLY` 的 `BusinessException`。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -Dtest=ProjectWritePolicyTest test`
Expected: FAIL，因为 `ProjectWritePolicy` 和 `PROJECT_READ_ONLY` 尚不存在。

- [ ] **Step 3: 实现最小策略和错误码**

实现一个无状态 final 类；只允许两个明确状态，其余状态统一抛出只读错误。`ErrorCode.PROJECT_READ_ONLY` 使用 HTTP 409 和中文消息。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\mvnw.cmd -Dtest=ProjectWritePolicyTest test`
Expected: PASS

### Task 2: 文档注册锁定真实项目状态

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/mapper/ProjectMapper.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/repository/ProjectRepository.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/DocumentRegistrationService.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/document/application/service/DocumentRegistrationStatusIntegrationTest.java`

**Interfaces:**
- Produces: `ProjectRepository.lockStatus(UUID projectId): Optional<ProjectStatus>`
- Consumes: `ProjectWritePolicy.requireWritable(ProjectStatus)`

- [ ] **Step 1: 写 PostgreSQL 失败测试**

插入 OWNER、`PREPARING` 项目和待注册文档，调用注册服务并断言记录创建；对 `COMPLETED` 断言 `PROJECT_READ_ONLY`；对随机项目 ID 断言 `PROJECT_NOT_FOUND`。

- [ ] **Step 2: 运行测试确认旧实现失败**

Run: `.\mvnw.cmd -Dtest=DocumentRegistrationStatusIntegrationTest test`
Expected: PREPARING 场景返回 `PROJECT_NOT_FOUND`。

- [ ] **Step 3: 最小修改锁查询和注册逻辑**

将 `status = 'ACTIVE'` 查询替换为按 ID `FOR UPDATE` 返回 `ProjectStatus`。不存在时抛 `PROJECT_NOT_FOUND`，存在时调用统一写策略。

- [ ] **Step 4: 运行集成测试确认通过**

Run: `.\mvnw.cmd -Dtest=DocumentRegistrationStatusIntegrationTest test`
Expected: PASS

### Task 3: 保护其他文档写入口并同步契约

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/DocumentApplicationService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/document/application/service/BatchReindexService.java`
- Modify: `ai-collab-frontend/src/api/api-result.ts`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/feature-matrix.md`

**Interfaces:**
- Consumes: `ProjectRepository.lockStatus` 或一个短事务项目写校验服务
- Produces: 所有文档写入口统一的 `PROJECT_READ_ONLY` 行为

- [ ] **Step 1: 增加删除、重试、重建索引的失败测试**

对 `COMPLETED` 或 `ARCHIVED` 项目调用每个写用例并断言 `PROJECT_READ_ONLY`；读取用例保持可用。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\mvnw.cmd -Dtest=*Document*Status* test`
Expected: 至少一个写入口没有返回 `PROJECT_READ_ONLY`。

- [ ] **Step 3: 实现共享写校验并同步错误契约**

在业务服务进入文档写用例时调用统一状态规则；OpenAPI 增加错误码说明；前端错误映射增加中文文案；功能矩阵写明项目状态约束。

- [ ] **Step 4: 运行相关测试和前端测试**

Run: `.\mvnw.cmd -Dtest=*Document* test`
Run: `pnpm test`
Expected: PASS

### Task 4: 真实上传与全量验证

**Files:**
- Modify only if evidence requires a scoped correction.

**Interfaces:**
- Verifies: `POST /api/v1/projects/{projectId}/documents`

- [ ] **Step 1: 在新建 PREPARING 项目上传小型 TXT**

通过真实登录/API 创建或选择 PREPARING 项目，上传临时 UTF-8 文本，断言 HTTP 202 和文档 ID。

- [ ] **Step 2: 等待处理终态**

轮询文档列表，接受 `READY`；如果外部 Embedding 关闭，按当前降级策略验证可解释终态。

- [ ] **Step 3: 运行全量验证**

Run: `.\mvnw.cmd test`
Run: `.\mvnw.cmd clean package -DskipTests`
Run: `pnpm test`
Run: `pnpm typecheck`
Run: `pnpm build`
Run: `git diff --check`

- [ ] **Step 4: 提交修复**

只暂存本计划涉及的代码、契约和事实文档，提交信息：
`fix(document): allow writes for preparing projects`
