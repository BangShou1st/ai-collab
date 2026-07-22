# AI Collab Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local-first collaboration platform for university competition teams with project RBAC, task dependencies, document RAG, cited answers, and user-confirmed AI task planning.

**Architecture:** Use one Spring Boot modular monolith organized by feature, a Vue 3 frontend, PostgreSQL with pgvector, MinIO, and optional Redis. External model suppliers are isolated behind `AiModelGateway`; model write access is prohibited, and task plans are applied only after validation and explicit user confirmation.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Spring Security, MyBatis-Plus, Flyway, PostgreSQL 17, pgvector, Redis 7.4, MinIO, Apache Tika, JUnit 5, Testcontainers, Vue 3, TypeScript, Element Plus, Vitest, Playwright, Docker Compose.

## Global Constraints

- Repository paths, ZIP paths, Java packages, database identifiers, and source filenames use ASCII only.
- Java root package is `com.shitulelv.aicollab`.
- REST API prefix is `/api/v1`.
- The application is a modular monolith; no service registry, gateway, or distributed transaction.
- Open registration is disabled; accounts are created through project invitations.
- Supported documents are PDF, DOCX, Markdown, and TXT, maximum 20 MB each.
- AI keys are environment variables and never stored in Git or returned to the frontend.
- Knowledge queries and vector SQL are scoped by `project_id`.
- Chat models may be switched manually; embedding changes require reindexing.
- AI task plans never write business data before explicit confirmation.
- Default automated tests use a fake model gateway and do not consume model quota.

---

## File Map

```text
ai-collab/
├── backend/src/main/java/com/shitulelv/aicollab/
│   ├── common/
│   ├── auth/
│   ├── user/
│   ├── project/
│   ├── work/
│   ├── document/
│   ├── knowledge/
│   ├── planning/
│   ├── dashboard/
│   ├── audit/
│   └── infrastructure/
├── backend/src/main/resources/
│   ├── db/migration/
│   └── mapper/
├── backend/src/test/
├── frontend/src/modules/
├── deploy/docker-compose.yml
└── docs/
```

### Task 1: Bootstrap Repository and Local Infrastructure

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/shitulelv/aicollab/AiCollabApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-local.yml`
- Create: `backend/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `frontend/package.json`
- Create: `frontend/src/main.ts`
- Create: `deploy/docker-compose.yml`
- Create: `.env.example`
- Test: `backend/src/test/java/com/shitulelv/aicollab/ContextLoadTest.java`

**Interfaces:**
- Produces a bootable backend on port 8080, frontend on 5173, PostgreSQL on 5432, Redis on 6379, and MinIO on 9000/9001.

- [ ] **Step 1: Create the context test**

```java
@SpringBootTest
class ContextLoadTest {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails before the application exists**

Run: `cd backend && ./mvnw -Dtest=ContextLoadTest test`

Expected: build failure because `AiCollabApplication` and project configuration do not exist.

- [ ] **Step 3: Create the Spring Boot application**

```java
package com.shitulelv.aicollab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class AiCollabApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCollabApplication.class, args);
    }
}
```

- [ ] **Step 4: Add Maven dependencies and copy the approved Flyway schema**

Include Spring Web, Validation, Security, Actuator, JDBC, Redis, Spring AI 1.1.8 BOM, OpenAI-compatible model starter, pgvector support, MyBatis-Plus, Flyway, PostgreSQL, MinIO, Apache Tika, Lombok only if the project consistently uses it, JUnit, Testcontainers, and Spring Security Test.

- [ ] **Step 5: Start infrastructure and run migrations**

Run:

```bash
cp .env.example .env
docker compose -f deploy/docker-compose.yml up -d
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Expected: `/actuator/health` returns `{"status":"UP"}` and Flyway records migration version 1.

- [ ] **Step 6: Run the context test**

Run: `cd backend && ./mvnw -Dtest=ContextLoadTest test`

Expected: 1 test passes.

- [ ] **Step 7: Commit**

```bash
git add .
git commit -m "chore: bootstrap local ai collab stack"
```

### Task 2: Common API, Errors, Request ID, and Security Skeleton

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/api/ApiResponse.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/error/ErrorCode.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/error/BusinessException.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/error/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/config/RequestIdFilter.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/common/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces `ApiResponse<T>` and stable error codes consumed by every Controller.

- [ ] **Step 1: Write the failing error response test**

```java
@WebMvcTest(controllers = ErrorProbeController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, RequestIdFilter.class})
class GlobalExceptionHandlerTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsStableErrorEnvelope() throws Exception {
        mvc.perform(get("/probe/error"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
```

- [ ] **Step 2: Run the test and confirm failure**

Run: `./mvnw -Dtest=GlobalExceptionHandlerTest test`

Expected: compilation failure because common API classes are missing.

- [ ] **Step 3: Implement the response contract**

```java
public record ApiResponse<T>(
    String code,
    String message,
    T data,
    UUID requestId,
    Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data, UUID requestId, Clock clock) {
        return new ApiResponse<>("OK", "success", data, requestId, Instant.now(clock));
    }
}
```

- [ ] **Step 4: Implement `RequestIdFilter` using `X-Request-Id` or a generated UUID and add it to MDC**

- [ ] **Step 5: Implement exception mapping for validation, authentication, authorization, conflict, unsupported media, provider timeout, and unknown errors**

- [ ] **Step 6: Run tests**

Run: `./mvnw -Dtest=GlobalExceptionHandlerTest test`

Expected: all tests pass and the error response contains a UUID request ID.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(common): add api envelope and error handling"
```

### Task 3: Authentication, Refresh Tokens, and Invitation Acceptance

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/auth/api/AuthController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/auth/application/AuthService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/auth/application/InvitationAcceptanceService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/auth/domain/JwtTokenService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/auth/infrastructure/RefreshTokenMapper.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/user/domain/AppUser.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/user/infrastructure/AppUserMapper.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/auth/application/AuthServiceTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/auth/api/AuthControllerSecurityTest.java`

**Interfaces:**
- Produces `CurrentUser`, access tokens, refresh-token rotation, and invitation-created users.

- [ ] **Step 1: Write tests for valid login, invalid password, disabled user, refresh rotation, and expired invitation**

- [ ] **Step 2: Run tests and confirm failure**

Run: `./mvnw -Dtest=AuthServiceTest,AuthControllerSecurityTest test`

Expected: compilation failure because auth services do not exist.

- [ ] **Step 3: Implement password verification and JWT claims**

```java
public record CurrentUser(UUID userId, String username, int tokenVersion) {}
```

Access token TTL is 2 hours. Refresh token TTL is 7 days, random 256-bit value, and only its SHA-256 hash is stored.

- [ ] **Step 4: Implement invitation acceptance in one transaction**

The service validates the code hash, status, expiry, optional invited email, username uniqueness, creates the user, adds the project member with invitation role, marks invitation ACCEPTED, and returns tokens.

- [ ] **Step 5: Implement endpoints `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/me`, `/invitations/{code}`, and `/invitations/{code}/accept`**

- [ ] **Step 6: Run tests**

Run: `./mvnw -Dtest=AuthServiceTest,AuthControllerSecurityTest test`

Expected: login and invitation cases pass; protected probe returns 401 without a token.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(auth): add invitation based authentication"
```

### Task 4: Projects, Members, and RBAC Guard

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/project/api/ProjectController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/project/api/ProjectMemberController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/project/application/ProjectService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/project/application/ProjectMemberService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/project/domain/ProjectRole.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/security/ProjectAccessGuard.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/project/application/ProjectMemberServiceTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/project/api/ProjectAuthorizationTest.java`

**Interfaces:**
- Produces `ProjectAccessGuard.requireMember`, `requireAdmin`, and `requireOwner` for all later modules.

- [ ] **Step 1: Write tests proving a creator becomes OWNER, MEMBER cannot invite, ADMIN can invite, and OWNER cannot be removed**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement the guard**

```java
public interface ProjectAccessGuard {
    ProjectRole requireMember(UUID projectId, UUID userId);
    void requireAdmin(UUID projectId, UUID userId);
    void requireOwner(UUID projectId, UUID userId);
}
```

- [ ] **Step 4: Implement project creation in one transaction with project and OWNER membership**

- [ ] **Step 5: Implement member invitation, role change, and removal rules**

- [ ] **Step 6: Run tests**

Run: `./mvnw -Dtest=ProjectMemberServiceTest,ProjectAuthorizationTest test`

Expected: all RBAC cases pass.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(project): add projects members and rbac"
```

### Task 5: Milestones, Tasks, Dependencies, and Comments

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/api/MilestoneController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/api/TaskController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/api/TaskCommentController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/application/MilestoneService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/application/TaskService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/work/domain/TaskDependencyPolicy.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/work/domain/TaskDependencyPolicyTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/work/application/TaskServiceTest.java`

**Interfaces:**
- Produces project-scoped milestone and task services consumed by planning confirmation and dashboard.

- [ ] **Step 1: Write dependency tests for direct self-cycle, two-node cycle, long cycle, and valid DAG**

```java
@Test
void rejectsLongCycle() {
    Map<UUID, Set<UUID>> graph = graphOf(a, Set.of(b), b, Set.of(c), c, Set.of(a));
    assertThatThrownBy(() -> policy.validate(graph))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("依赖形成环");
}
```

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement Kahn cycle detection and same-project checks**

- [ ] **Step 4: Implement task state rules**

Allowed transitions: any non-final state may move to another non-final state; DONE may move back to IN_PROGRESS by ADMIN; CANCELED may only move to IN_PROGRESS by ADMIN. Setting DONE writes `completed_at`; leaving DONE clears it.

- [ ] **Step 5: Implement milestones, tasks, dependency replacement, and flat comments**

- [ ] **Step 6: Run tests and a PostgreSQL integration test**

Run: `./mvnw -Dtest=TaskDependencyPolicyTest,TaskServiceTest test`

Expected: all domain and authorization tests pass.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(work): add milestones tasks dependencies and comments"
```

### Task 6: Audit Log and Dashboard

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/audit/AuditPublisher.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/audit/application/AuditQueryService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/dashboard/application/DashboardService.java`
- Create: `backend/src/main/resources/mapper/dashboard/DashboardMapper.xml`
- Test: `backend/src/test/java/com/shitulelv/aicollab/dashboard/application/DashboardServiceIT.java`

**Interfaces:**
- Consumes project and work data.
- Produces dashboard statistics and admin-visible audit pages.

- [ ] **Step 1: Write an integration test with tasks in every status and one overdue task**

- [ ] **Step 2: Run test and confirm failure**

- [ ] **Step 3: Implement audit publishing after successful transactions**

- [ ] **Step 4: Implement one grouped SQL query for task counts and one query for milestone progress**

- [ ] **Step 5: Ensure dashboard access requires MEMBER and audit access requires ADMIN**

- [ ] **Step 6: Run test**

Run: `./mvnw -Dtest=DashboardServiceIT test`

Expected: counts, completion rate, overdue count, and recent activity match fixtures.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(dashboard): add project statistics and audit logs"
```

### Task 7: MinIO Document Lifecycle

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/storage/ObjectStorageGateway.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/storage/MinioObjectStorageGateway.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/api/DocumentController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/application/DocumentService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/domain/DocumentFilePolicy.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/document/domain/DocumentFilePolicyTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/document/infrastructure/MinioObjectStorageGatewayIT.java`

**Interfaces:**
- Produces project-scoped document metadata and object retrieval for indexing.

- [ ] **Step 1: Write tests for allowed MIME types, extension mismatch, zero-byte files, and files above 20 MB**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement object keys**

```java
String objectKey = "projects/%s/documents/%s/%s".formatted(
    projectId, documentId, FilenameSanitizer.sanitize(originalFilename));
```

- [ ] **Step 4: Implement upload transaction and compensation**

Insert UPLOADED metadata first, upload to MinIO, then submit indexing. If MinIO upload fails, delete metadata and return a storage error.

- [ ] **Step 5: Implement five-minute presigned downloads after membership checks**

- [ ] **Step 6: Run tests**

Run: `./mvnw -Dtest=DocumentFilePolicyTest,MinioObjectStorageGatewayIT test`

Expected: allowed files upload and download; invalid files fail before storage.

- [ ] **Step 7: Commit**

```bash
git add backend
git commit -m "feat(document): add minio document lifecycle"
```

### Task 8: Document Parsing, Chunking, and Recovery

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/application/DocumentIndexingFacade.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/application/DocumentIndexingJob.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/domain/TextCleaner.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/domain/HeadingAwareChunker.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/infrastructure/TikaDocumentParser.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/document/application/StaleJobRecovery.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/document/domain/HeadingAwareChunkerTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/document/application/DocumentIndexingJobTest.java`

**Interfaces:**
- Produces cleaned chunks for the embedding stage.

- [ ] **Step 1: Write chunking tests for headings, oversized paragraphs, overlap, and short trailing chunks**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement parser limits: 60 seconds, 2,000,000 characters, no OCR**

- [ ] **Step 4: Implement target 800, max 1,200, overlap 120 character chunking**

- [ ] **Step 5: Implement status changes UPLOADED → PARSING → INDEXING and error transition to FAILED**

- [ ] **Step 6: Implement startup recovery marking jobs stale for more than 10 minutes as FAILED**

- [ ] **Step 7: Run tests**

Run: `./mvnw -Dtest=HeadingAwareChunkerTest,DocumentIndexingJobTest test`

Expected: chunk boundaries and all status transitions match fixtures.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(document): add parsing chunking and recovery"
```

### Task 9: AI Gateway, Embeddings, and pgvector Retrieval

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/AiModelGateway.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/SpringAiModelGateway.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/AiProviderProperties.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/vector/VectorSearchGateway.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/infrastructure/vector/PgVectorSearchGateway.java`
- Create: `backend/src/main/resources/mapper/document/DocumentChunkMapper.xml`
- Test: `backend/src/test/java/com/shitulelv/aicollab/infrastructure/vector/PgVectorSearchGatewayIT.java`

**Interfaces:**
- Produces model-neutral chat, structured generation, embedding, and project-scoped vector search.

- [ ] **Step 1: Write a pgvector integration test with two projects and prove only current-project chunks are returned**

- [ ] **Step 2: Run test and confirm failure**

- [ ] **Step 3: Define the model gateway**

```java
public interface AiModelGateway {
    ChatResult chat(ChatCommand command);
    <T> T structured(StructuredChatCommand<T> command);
    EmbeddingResult embed(EmbeddingCommand command);
}
```

- [ ] **Step 4: Implement provider exception mapping for timeout, quota, authentication, and generic 5xx**

- [ ] **Step 5: Implement exact cosine SQL with project, document, provider, model, and dimension filters**

- [ ] **Step 6: Complete indexing by embedding each chunk in batches and marking the document READY**

- [ ] **Step 7: Run tests**

Run: `./mvnw -Dtest=PgVectorSearchGatewayIT test`

Expected: cross-project rows are never returned and similarities are ordered descending.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(ai): add model gateway embeddings and vector search"
```

### Task 10: Knowledge Sessions, Cited Answers, and Refusal

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/knowledge/api/KnowledgeController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/knowledge/application/KnowledgeQaFacade.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/knowledge/application/KnowledgeQaService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/KnowledgePromptBuilder.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/knowledge/domain/CitationValidator.java`
- Create: `backend/src/test/java/com/shitulelv/aicollab/support/FakeAiModelGateway.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/knowledge/application/KnowledgeQaServiceTest.java`

**Interfaces:**
- Consumes vector retrieval and the AI gateway.
- Produces persisted user and assistant messages with citations.

- [ ] **Step 1: Write tests for cited answer, no-result refusal, invalid citation removal, and session ownership**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement direct refusal without chat call when no chunk reaches similarity 0.55**

- [ ] **Step 4: Implement source labels `[S1]` to `[S5]`, maximum 8,000 context characters, and untrusted-source prompt wording**

- [ ] **Step 5: Validate model citations against supplied source IDs and persist valid citations**

- [ ] **Step 6: Add per-user hourly rate limiting**

- [ ] **Step 7: Run tests**

Run: `./mvnw -Dtest=KnowledgeQaServiceTest test`

Expected: fake model is called only when evidence exists, and no cross-session access is allowed.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(knowledge): add cited rag question answering"
```

### Task 11: Generate and Validate AI Task Plan Drafts

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/api/TaskPlanController.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanningFacade.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationJob.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraft.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanValidator.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/PlanningReadTools.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanValidatorTest.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationJobTest.java`

**Interfaces:**
- Produces READY or FAILED editable plans, not formal tasks.

- [ ] **Step 1: Write validator tests for duplicate temp keys, missing milestones, unknown members, invalid dates, self-dependency, long dependency cycle, and more than maxTasks**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement records exactly as documented in `06-ai-task-planning-design.md`**

- [ ] **Step 4: Implement read-only tools for project context, members, existing work, and document search with a four-call limit**

- [ ] **Step 5: Generate structured output and allow one repair call containing validation errors**

- [ ] **Step 6: Persist raw response, normalized milestones, tasks, dependencies, assumptions, risks, model, tokens, and status**

- [ ] **Step 7: Run tests**

Run: `./mvnw -Dtest=TaskPlanValidatorTest,TaskPlanGenerationJobTest test`

Expected: invalid first output is repaired once; a second invalid output becomes FAILED.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(planning): generate and validate task plan drafts"
```

### Task 12: Edit, Confirm, and Idempotently Apply Task Plans

**Files:**
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanDraftService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanApplyService.java`
- Create: `backend/src/main/java/com/shitulelv/aicollab/common/idempotency/IdempotencyService.java`
- Test: `backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanApplyServiceIT.java`

**Interfaces:**
- Consumes `MilestoneService`, `TaskService`, and `TaskDependencyPolicy`.
- Produces created milestone IDs and task IDs.

- [ ] **Step 1: Write integration tests for successful apply, rollback on invalid dependency, same-key replay, different-request key conflict, and second confirmation rejection**

- [ ] **Step 2: Run tests and confirm failure**

- [ ] **Step 3: Implement full-draft replacement only for READY plans**

- [ ] **Step 4: Implement `confirm` with a project-scoped database lock and one transaction**

- [ ] **Step 5: Map milestone and task temp keys to generated UUIDs before inserting dependencies**

- [ ] **Step 6: Persist idempotency response for 24 hours and return it on exact replay**

- [ ] **Step 7: Run tests**

Run: `./mvnw -Dtest=TaskPlanApplyServiceIT test`

Expected: one confirmation creates one set of records; retries do not duplicate data.

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(planning): confirm task plans transactionally"
```

### Task 13: Vue Frontend Main Workflows

**Files:**
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/stores/project.ts`
- Create: `frontend/src/modules/auth/pages/LoginPage.vue`
- Create: `frontend/src/modules/auth/pages/InvitationPage.vue`
- Create: `frontend/src/modules/project/pages/ProjectListPage.vue`
- Create: `frontend/src/modules/dashboard/pages/DashboardPage.vue`
- Create: `frontend/src/modules/work/pages/TaskBoardPage.vue`
- Create: `frontend/src/modules/document/pages/DocumentPage.vue`
- Create: `frontend/src/modules/knowledge/pages/KnowledgePage.vue`
- Create: `frontend/src/modules/planning/pages/TaskPlanningPage.vue`
- Test: `frontend/src/modules/planning/TaskPlanEditor.spec.ts`
- Test: `frontend/e2e/main-flow.spec.ts`

**Interfaces:**
- Consumes OpenAPI endpoints and role values.
- Produces the complete user demonstration flow.

- [ ] **Step 1: Write a component test for editing tasks and dependencies in a plan draft**

- [ ] **Step 2: Run `pnpm test` and confirm failure**

- [ ] **Step 3: Implement Axios envelope handling, access-token memory storage, and one-at-a-time refresh**

- [ ] **Step 4: Implement project layout and role-aware navigation**

- [ ] **Step 5: Implement task board without drag-and-drop, document status polling, cited answers, and three-step planning UI**

- [ ] **Step 6: Write Playwright main flow using a fake AI backend profile**

- [ ] **Step 7: Run frontend tests and build**

Run:

```bash
cd frontend
pnpm test
pnpm build
pnpm exec playwright test
```

Expected: component, build, and main-flow tests pass.

- [ ] **Step 8: Commit**

```bash
git add frontend
git commit -m "feat(web): add core collaboration and ai workflows"
```

### Task 14: Evaluation, Packaging, Documentation, and Release

**Files:**
- Create: `backend/src/test/resources/evaluation/rag-cases.jsonl`
- Create: `backend/src/test/resources/evaluation/planning-cases.jsonl`
- Create: `backend/src/test/java/com/shitulelv/aicollab/evaluation/RagEvaluationTest.java`
- Create: `backend/src/test/java/com/shitulelv/aicollab/evaluation/TaskPlanEvaluationTest.java`
- Create: `docs/evaluation-report.md`
- Create: `docs/demo-script.md`
- Modify: `README.md`
- Modify: `api/openapi.yaml`

**Interfaces:**
- Produces reproducible evidence for GitHub and resume claims.

- [ ] **Step 1: Add 60 RAG cases and 20 task-planning cases with no private competition data**

- [ ] **Step 2: Implement evaluation tests using fake deterministic embeddings and model outputs**

- [ ] **Step 3: Run the complete backend and frontend verification**

```bash
cd backend
./mvnw clean verify
cd ../frontend
pnpm test
pnpm build
pnpm exec playwright test
```

Expected: every command exits 0.

- [ ] **Step 4: Start from a clean local volume and follow the README**

```bash
docker compose -f deploy/docker-compose.yml down -v
docker compose -f deploy/docker-compose.yml up -d
```

Create the local owner, upload one document of each supported type, ask one answerable and one unanswerable question, generate a plan, edit it, confirm it, and verify formal tasks appear.

- [ ] **Step 5: Record actual metrics without inventing values**

Record retrieval hit rate, citation correctness, refusal rate, structured output validity, task-apply idempotency result, non-AI API p95, and test counts.

- [ ] **Step 6: Create release tag**

```bash
git add .
git commit -m "docs: add evaluation and release materials"
git tag -a v0.1.0 -m "AI Collab local MVP"
```

## Plan Self-Review Result

- Product requirements are covered by Tasks 1–14.
- Project isolation is tested in Tasks 4, 9, and 10.
- File type, parser, and size constraints are covered in Tasks 7 and 8.
- RAG retrieval, citations, and refusal are covered in Tasks 9 and 10.
- Structured planning, repair, confirmation, transaction, and idempotency are covered in Tasks 11 and 12.
- Default tests avoid real model quota through `FakeAiModelGateway`.
- Deployment, documentation, evaluation, and release evidence are covered in Task 14.
