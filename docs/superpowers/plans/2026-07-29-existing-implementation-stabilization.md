# Existing Implementation Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize every currently implemented AI Collab workflow, finish the existing Phase 09 Dashboard/Audit work, close backend-only project operations in the Chinese frontend, and replace inaccurate documentation with evidence-backed project guidance.

**Architecture:** Keep the modular Spring Boot monolith and Vue module boundaries unchanged. Add tests at domain/service, PostgreSQL integration, API-contract, and user-visible component levels; make only the smallest production changes required by those tests; then derive documentation from the verified code rather than from historical phase claims.

**Tech Stack:** Java 21, Spring Boot 4.1, MyBatis-Plus, PostgreSQL/pgvector Testcontainers, JUnit 5, AssertJ, Mockito, Vue 3, TypeScript 5.8, Element Plus, Pinia, Axios, Vitest, Vue Test Utils, OpenAPI 3.

## Global Constraints

- Work on the current local `main` branch and preserve all pre-existing uncommitted changes.
- Do not implement features that have no executable code yet.
- Do not modify existing Flyway migration files V1–V12.
- Write a failing regression test before every production behavior change.
- Every project-scoped SQL statement must explicitly constrain `project_id`.
- Backend authorization remains the security boundary; frontend role checks only improve interaction.
- All user-visible interface copy is Simplified Chinese except necessary technical identifiers such as AI, API, PDF, DOCX, Markdown, MIME types, and model names.
- Do not add project type, four-state project lifecycle, member self-leave, owner transfer, administrator-created accounts, streamed RAG answers, answer feedback, notifications, reports, Gantt views, or a runtime Agent framework.
- Never print or persist passwords, tokens, cookies, API keys, prompts, document bodies, or secrets during tests and smoke verification.
- Each task is committed independently after its complete verification command passes.

---

## File Structure

### Backend

- `planning/*IntegrationTest.java`: migration contract regression coverage.
- `project/application/service/AuditSummaryFormatter.java`: canonical mapping from actual audit action/entity codes to Chinese summaries.
- `project/application/service/AuditService.java`: bounded, safe structured audit detail.
- `project/application/service/AuditLogQueryService.java`: authorization and safe view conversion.
- `project/infrastructure/mapper/AuditLogMapper.java`: project-scoped pagination and recent activity queries.
- `project/infrastructure/mapper/ProjectDashboardMapper.java`: project-scoped Dashboard aggregation.
- `project/api/controller/*Dashboard*`, `*Audit*`: HTTP boundary and pagination validation.
- `src/test/java/.../project/**`: focused unit and PostgreSQL integration tests for Phase 09.

### Frontend

- `modules/dashboard/**`: Dashboard API, types, component, and tests.
- `modules/audit/**`: Audit API, types, component, and tests.
- `modules/project/project-api.ts`: complete existing project update/delete contract.
- `modules/project/ProjectListView.vue`: existing backend project update/delete workflow.
- `modules/project/ProjectListView.test.ts`: user-visible update/delete behavior.
- `shared/display-labels.ts` and tests: centralized safe Chinese display mapping.
- `shared/AppShell.vue`, `router.ts`: Chinese product shell, Phase 09 routes, role-sensitive navigation.
- `src/**/*.test.ts`: module contract tests added only for workflows touched by this stabilization.

### Verification and Documentation

- `scripts/smoke-existing.ps1`: credential-safe local API smoke runner.
- `docs/feature-matrix.md`: authoritative implementation status.
- `CLAUDE.md`: concise startup context.
- `docs/architecture.md`, `docs/database.md`, `docs/api/openapi.yaml`, `docs/FUTURE_ROADMAP.md`: verified system facts.
- `docs/development/README.md`: development-document entry point.
- `docs/development/backend-conventions.md`: backend interfaces and safety rules.
- `docs/development/frontend-conventions.md`: frontend contracts and Chinese UI rules.
- `docs/development/agent-design.md`: current controlled workflow versus future Agent architecture.
- `docs/development/claude-mimo-task-template.md`: strict single-task prompt template.
- `docs/learning/phase-*.md`: historical-status banners only; teaching content remains.
- Delete `docs/prompts/Phase09-Claude-MiMo-Prompts.md` after its still-valid constraints have been migrated.

---

### Task 1: Repair the V12 migration contract tests

**Files:**
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanSpringBeanPostgresIntegrationTest.java:103`
- Modify: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/infrastructure/Phase08MigrationSafetyIntegrationTest.java:168`

**Interfaces:**
- Consumes: Flyway migration files `V1__init_schema.sql` through `V12__add_comment_to_task_plan_event.sql`.
- Produces: a test contract that requires the complete ordered migration history `1..12` and latest version `12`.

- [ ] **Step 1: Re-run the two failing tests and preserve the red evidence**

Run:

```powershell
cd E:\ai-collab\ai-collab-backend
.\mvnw.cmd -Dtest=TaskPlanSpringBeanPostgresIntegrationTest,Phase08MigrationSafetyIntegrationTest test
```

Expected: five failures report expected V11 but actual V12.

- [ ] **Step 2: Update only the stale version assertions**

Change:

```java
assertThat(jdbc.queryForObject(
        "select version from flyway_schema_history where success=true order by installed_rank desc limit 1",
        String.class)).isEqualTo("12");
```

and:

```java
assertThat(jdbc.queryForList("""
        select version
        from flyway_schema_history
        where success = true
        order by installed_rank
        """, String.class))
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
```

Do not rename the Phase 08 migration safety test during this task; its scope is historical migration compatibility and the assertion still protects it.

- [ ] **Step 3: Verify the focused migration tests**

Run the command from Step 1.

Expected: both test classes pass with zero failures.

- [ ] **Step 4: Commit**

```powershell
git add ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanSpringBeanPostgresIntegrationTest.java ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/infrastructure/Phase08MigrationSafetyIntegrationTest.java
git commit -m "test(database): align migration assertions with v12"
```

---

### Task 2: Make audit summaries match the action codes the application actually writes

**Files:**
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/application/service/AuditSummaryFormatterTest.java`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/application/service/AuditServiceTest.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/AuditSummaryFormatter.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/AuditService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/mapper/AuditLogMapper.java`

**Interfaces:**
- Consumes: actual action codes emitted by project, work, document, planning, and invitation services.
- Produces: `String AuditSummaryFormatter.format(String userDisplayName, String action, String entityType, String detailJson)` with safe Chinese fallback; `AuditService.write(..., Map<String, ?> detail)` with valid bounded JSON.

- [ ] **Step 1: Write the failing action-code parameterized test**

Use actual application codes:

```java
@ParameterizedTest
@CsvSource({
        "PROJECT_INVITATION_CREATED,PROJECT_INVITATION,创建了项目邀请",
        "PROJECT_MEMBER_ROLE_CHANGED,PROJECT_MEMBER,变更了成员角色",
        "PROJECT_MEMBER_REMOVED,PROJECT_MEMBER,移除了项目成员",
        "TASK_COMMENT_CREATED,TASK_COMMENT,发表了任务评论",
        "TASK_COMMENT_UPDATED,TASK_COMMENT,更新了任务评论",
        "TASK_COMMENT_DELETED,TASK_COMMENT,删除了任务评论",
        "DOCUMENT_INDEXED,PROJECT_DOCUMENT,完成了文档处理",
        "DOCUMENT_PROCESSING_FAILED,PROJECT_DOCUMENT,文档处理失败",
        "DOCUMENT_RETRY_REQUESTED,PROJECT_DOCUMENT,重新处理了文档",
        "TASK_PLAN_CONFIRMED,AI_TASK_PLAN,确认了 AI 任务规划"
})
void formatsActualApplicationCodes(String action, String entityType, String phrase) {
    String summary = formatter.format("张三", action, entityType, "{}");
    assertThat(summary).isEqualTo("张三" + phrase);
}
```

Also assert a named task:

```java
assertThat(formatter.format(
        "张三", "TASK_CREATED", "TASK",
        "{\"title\":\"实现登录\"}"))
        .isEqualTo("张三创建了任务「实现登录」");
```

- [ ] **Step 2: Write the failing safe-detail tests**

Mock `AuditLogMapper` and capture the `detail` argument passed to `insertWithDetail`:

```java
@Test
void writesValidBoundedJsonWithoutCallingArbitraryToString() throws Exception {
    AuditService service = new AuditService(mapper, new ObjectMapper());
    service.write(PROJECT_ID, USER_ID, "TASK_CREATED", "TASK", TASK_ID,
            Map.of("title", "A".repeat(500), "count", 2));

    verify(mapper).insertWithDetail(
            any(), eq(PROJECT_ID), eq(USER_ID), eq("TASK_CREATED"), eq("TASK"),
            eq(TASK_ID), detail.capture(), any());
    JsonNode parsed = new ObjectMapper().readTree(detail.getValue());
    assertThat(parsed.get("title").asText()).endsWith("…").hasSize(201);
    assertThat(parsed.get("count").asInt()).isEqualTo(2);
    assertThat(detail.getValue().length()).isLessThanOrEqualTo(4096);
}
```

Add cases for empty detail and serialization failure. Expected fallback is exactly `{}`.

- [ ] **Step 3: Verify the new tests fail for the expected reasons**

Run:

```powershell
cd E:\ai-collab\ai-collab-backend
.\mvnw.cmd -Dtest=AuditSummaryFormatterTest,AuditServiceTest test
```

Expected: formatter cases fail because the current maps use different codes; safe-detail cases expose any contract mismatch.

- [ ] **Step 4: Replace formatter maps with canonical actual codes**

The mapping must include every code returned by:

```powershell
Get-ChildItem src/main/java -Recurse -File -Include *.java |
  Select-String -Pattern 'audit\.write\('
```

Use precise entity labels:

```java
Map.entry("PROJECT_DOCUMENT", "文档"),
Map.entry("PROJECT_MEMBER", "项目成员"),
Map.entry("PROJECT_INVITATION", "项目邀请"),
Map.entry("TASK_COMMENT", "任务评论"),
Map.entry("AI_TASK_PLAN", "AI 任务规划")
```

Unknown action fallback remains `执行了操作`; unknown entity text must never expose the raw code.

- [ ] **Step 5: Make detail serialization satisfy the bounded valid-JSON contract**

Keep the `Map<String, ?>` public interface. Sanitize string values to 200 characters plus `…`, preserve primitive numbers/booleans, and fall back to `{}` if the final JSON exceeds 4096 characters or serialization fails.

- [ ] **Step 6: Verify focused tests and existing compilation**

Run:

```powershell
.\mvnw.cmd -Dtest=AuditSummaryFormatterTest,AuditServiceTest test
.\mvnw.cmd -DskipTests package
```

Expected: tests pass and backend package compilation succeeds.

- [ ] **Step 7: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/AuditSummaryFormatter.java ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/AuditService.java ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/mapper/AuditLogMapper.java ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/application/service/AuditSummaryFormatterTest.java ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/application/service/AuditServiceTest.java
git commit -m "fix(audit): align Chinese summaries with emitted events"
```

---

### Task 3: Prove and finish Phase 09 backend authorization, aggregation, and isolation

**Files:**
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/application/service/ProjectDashboardAuditPostgresIntegrationTest.java`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/project/api/controller/ProjectDashboardAuditControllerTest.java`
- Modify as required by failing tests:
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/api/controller/ProjectDashboardController.java`
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/api/controller/AuditLogController.java`
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/ProjectDashboardQueryService.java`
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/application/service/AuditLogQueryService.java`
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/mapper/ProjectDashboardMapper.java`
  - `ai-collab-backend/src/main/java/com/shitulelv/aicollab/project/infrastructure/mapper/AuditLogMapper.java`
  - Phase 09 view records under `project/application/view/`

**Interfaces:**
- Produces: `GET /api/v1/projects/{projectId}/dashboard`.
- Produces: `GET /api/v1/projects/{projectId}/audit-logs?page=1&size=20`.
- Guarantees: member-readable Dashboard; admin-only full Audit; stable project-scoped results.

- [ ] **Step 1: Write PostgreSQL fixtures for two projects**

The test fixture must insert:

```java
UUID projectA = UUID.randomUUID();
UUID projectB = UUID.randomUUID();
UUID owner = UUID.randomUUID();
UUID admin = UUID.randomUUID();
UUID member = UUID.randomUUID();
UUID outsider = UUID.randomUUID();
```

For project A insert tasks in `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE`, and `CANCELED`, including one overdue active task; one milestone; one document; and two audit rows. Insert distinct tasks/documents/audit rows for project B so cross-project leakage is observable.

- [ ] **Step 2: Write failing Dashboard assertions**

Assert:

```java
DashboardView view = dashboard.getDashboard(projectA, member);
assertThat(view.project().memberCount()).isEqualTo(3);
assertThat(view.tasks().total()).isEqualTo(5);
assertThat(view.tasks().done()).isEqualTo(1);
assertThat(view.tasks().canceled()).isEqualTo(1);
assertThat(view.tasks().completionRate()).isEqualTo(0.250d);
assertThat(view.recentTasks()).allMatch(task -> projectATaskIds.contains(task.id()));
assertThat(view.recentDocuments()).allMatch(doc -> projectADocumentIds.contains(doc.id()));
assertThat(view.recentActivities()).allMatch(activity -> projectAAuditIds.contains(activity.id()));
```

Also assert outsider access throws `BusinessException` with `PROJECT_NOT_FOUND` according to the existing membership guard.

- [ ] **Step 3: Write failing Audit assertions**

Assert OWNER and ADMIN can page; MEMBER receives `PROJECT_ADMIN_REQUIRED`; `size=101`, `size=0`, and `page=0` produce validation errors at the Controller boundary; rows order by `createdAt DESC, id DESC`; Dashboard activity does not contain `detail` or `requestId`.

- [ ] **Step 4: Run the new tests and confirm red behavior**

Run:

```powershell
cd E:\ai-collab\ai-collab-backend
.\mvnw.cmd -Dtest=ProjectDashboardAuditPostgresIntegrationTest,ProjectDashboardAuditControllerTest test
```

Expected: failures identify missing validation, mapping, action summary, SQL isolation, or constructor binding defects.

- [ ] **Step 5: Apply the smallest backend fixes**

Required SQL properties:

```sql
WHERE t.project_id = #{projectId}
WHERE d.project_id = #{projectId}
WHERE a.project_id = #{projectId}
```

Completion semantics:

```sql
DONE / count(status <> 'CANCELED')
```

Overdue semantics:

```sql
due_date < CURRENT_DATE AND status NOT IN ('DONE', 'CANCELED')
```

Controller parameter contract:

```java
@RequestParam(defaultValue = "1") @Min(1) int page,
@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
```

If method validation is needed, annotate the controller with `@Validated`.

- [ ] **Step 6: Verify Phase 09 backend tests**

Run the command from Step 4.

Expected: both classes pass, including the two-project leakage checks.

- [ ] **Step 7: Verify all backend tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: zero failures and zero errors.

- [ ] **Step 8: Commit**

Stage only Phase 09 backend implementation and tests, then:

```powershell
git commit -m "feat(project): complete dashboard and audit backend"
```

---

### Task 4: Finish Phase 09 frontend behavior and Chinese presentation

**Files:**
- Create: `ai-collab-frontend/src/modules/dashboard/dashboard-api.test.ts`
- Create: `ai-collab-frontend/src/modules/dashboard/DashboardView.test.ts`
- Create: `ai-collab-frontend/src/modules/audit/audit-api.test.ts`
- Create: `ai-collab-frontend/src/modules/audit/AuditLogView.test.ts`
- Create: `ai-collab-frontend/src/stores/project-context-store.test.ts`
- Modify as required:
  - `ai-collab-frontend/src/modules/dashboard/dashboard-api.ts`
  - `ai-collab-frontend/src/modules/dashboard/DashboardView.vue`
  - `ai-collab-frontend/src/modules/dashboard/types.ts`
  - `ai-collab-frontend/src/modules/audit/audit-api.ts`
  - `ai-collab-frontend/src/modules/audit/AuditLogView.vue`
  - `ai-collab-frontend/src/modules/audit/types.ts`
  - `ai-collab-frontend/src/stores/project-context-store.ts`
  - `ai-collab-frontend/src/router.ts`
  - `ai-collab-frontend/src/shared/AppShell.vue`

**Interfaces:**
- Consumes: Phase 09 backend responses from Task 3.
- Produces: Chinese Dashboard and Audit pages with role-correct navigation and stable empty/error states.

- [ ] **Step 1: Write API contract tests**

Mock `httpClient` and assert:

```ts
expect(httpClient.get).toHaveBeenCalledWith(`/projects/${projectId}/dashboard`)
expect(httpClient.get).toHaveBeenCalledWith(
  `/projects/${projectId}/audit-logs`,
  { params: { page: 2, size: 20 } },
)
```

- [ ] **Step 2: Write Dashboard component tests**

Mount with stubbed API data and assert visible Chinese labels:

```ts
expect(wrapper.text()).toContain('项目概览')
expect(wrapper.text()).toContain('任务总数')
expect(wrapper.text()).toContain('进行中')
expect(wrapper.text()).toContain('已完成')
expect(wrapper.text()).toContain('逾期任务')
expect(wrapper.text()).not.toContain('IN_PROGRESS')
expect(wrapper.text()).not.toContain('ACTIVE')
```

Add empty-state and normalized API error cases.

- [ ] **Step 3: Write Audit and project-context tests**

Assert:

- OWNER/ADMIN context shows “操作日志”;
- MEMBER context does not render the link;
- changing `projectId` reloads context;
- Audit page sends current `page` and `size`;
- raw `TASK_PLAN_CONFIRMED`, `PROJECT_MEMBER_ROLE_CHANGED`, and `PROJECT_DOCUMENT` values are never displayed;
- empty history shows a Chinese empty state.

- [ ] **Step 4: Run focused tests and confirm red**

Run:

```powershell
cd E:\ai-collab\ai-collab-frontend
pnpm test -- src/modules/dashboard src/modules/audit src/stores/project-context-store.test.ts
```

Expected: new tests fail where API unwrapping, labels, role navigation, or empty states are incomplete.

- [ ] **Step 5: Implement the minimal component/API fixes**

All status rendering must call the centralized label functions. All API exceptions must pass through `normalizeApiError`. Do not add chart libraries, drag/drop, caching, or new Dashboard widgets.

- [ ] **Step 6: Verify focused and full frontend checks**

Run:

```powershell
pnpm test -- src/modules/dashboard src/modules/audit src/stores/project-context-store.test.ts
pnpm test
pnpm typecheck
pnpm build
```

Expected: all commands exit 0; build-size warnings may remain documented but are not failures.

- [ ] **Step 7: Commit**

```powershell
git add ai-collab-frontend/src/modules/dashboard ai-collab-frontend/src/modules/audit ai-collab-frontend/src/stores/project-context-store.ts ai-collab-frontend/src/stores/project-context-store.test.ts ai-collab-frontend/src/router.ts ai-collab-frontend/src/shared/AppShell.vue
git commit -m "feat(frontend): complete Chinese dashboard and audit views"
```

---

### Task 5: Close the existing project update and delete frontend workflows

**Files:**
- Create: `ai-collab-frontend/src/modules/project/project-api.test.ts`
- Create: `ai-collab-frontend/src/modules/project/ProjectListView.test.ts`
- Modify: `ai-collab-frontend/src/modules/project/project-api.ts`
- Modify: `ai-collab-frontend/src/modules/project/ProjectListView.vue`
- Modify: `ai-collab-frontend/src/modules/project/types.ts`
- Modify if a missing mapping is exposed: `ai-collab-frontend/src/api/api-result.ts`

**Interfaces:**
- Consumes: existing `PATCH /api/v1/projects/{projectId}` and `DELETE /api/v1/projects/{projectId}`.
- Produces:

```ts
update(projectId: string, payload: {
  name: string
  description: string
  startDate: string | null
  dueDate: string | null
  status: 'ACTIVE' | 'ARCHIVED'
  version: number
}): Promise<ApiResult<Project>>

remove(projectId: string): Promise<void>
```

- [ ] **Step 1: Write API contract tests**

```ts
await projectApi.update('p1', {
  name: '竞赛项目',
  description: '',
  startDate: null,
  dueDate: null,
  status: 'ACTIVE',
  version: 3,
})
expect(httpClient.patch).toHaveBeenCalledWith('/projects/p1', expect.objectContaining({ version: 3 }))

await projectApi.remove('p1')
expect(httpClient.delete).toHaveBeenCalledWith('/projects/p1')
```

- [ ] **Step 2: Write user-visible component tests**

Cover:

- OWNER sees “编辑项目”和“删除项目”;
- ADMIN/MEMBER do not see owner-only operations;
- edit dialog is prefilled;
- invalid date range prevents submission;
- submit sends the current version;
- successful update refreshes the list and shows “项目已更新”;
- delete requires confirmation;
- canceled confirmation makes no API request;
- successful deletion refreshes the list and shows “项目已删除”;
- `VERSION_CONFLICT`, `PROJECT_DOCUMENTS_EXIST`, and `PROJECT_CONFIRMED_PLANS_EXIST` render safe Chinese messages.

- [ ] **Step 3: Run focused tests and confirm red**

Run:

```powershell
cd E:\ai-collab\ai-collab-frontend
pnpm test -- src/modules/project/project-api.test.ts src/modules/project/ProjectListView.test.ts
```

Expected: failures because update/remove methods and UI controls do not exist.

- [ ] **Step 4: Implement API methods and owner-only UI**

Use separate create/edit form state or an explicit mode:

```ts
const dialogMode = ref<'create' | 'edit'>('create')
const editingProjectId = ref('')
const deletingProjectId = ref('')
```

Do not add project type or new statuses. Keep status choices:

```ts
[
  { value: 'ACTIVE', label: '进行中' },
  { value: 'ARCHIVED', label: '已归档' },
]
```

Confirm deletion with:

```ts
await ElMessageBox.confirm(
  `删除项目“${project.name}”后无法恢复，确认继续吗？`,
  '删除项目',
  { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
)
```

- [ ] **Step 5: Verify focused and full frontend tests**

Run:

```powershell
pnpm test -- src/modules/project/project-api.test.ts src/modules/project/ProjectListView.test.ts
pnpm test
pnpm typecheck
pnpm build
```

Expected: all commands exit 0.

- [ ] **Step 6: Commit**

```powershell
git add ai-collab-frontend/src/modules/project/project-api.ts ai-collab-frontend/src/modules/project/ProjectListView.vue ai-collab-frontend/src/modules/project/types.ts ai-collab-frontend/src/modules/project/project-api.test.ts ai-collab-frontend/src/modules/project/ProjectListView.test.ts ai-collab-frontend/src/api/api-result.ts
git commit -m "feat(project): close update and delete frontend workflows"
```

---

### Task 6: Enforce centralized Chinese display and safe error fallbacks

**Files:**
- Create: `ai-collab-frontend/src/shared/display-labels.test.ts`
- Modify: `ai-collab-frontend/src/shared/display-labels.ts`
- Modify: `ai-collab-frontend/src/api/api-result.ts`
- Modify user-visible copy in:
  - `ai-collab-frontend/src/shared/AppShell.vue`
  - `ai-collab-frontend/src/modules/planning/PlanningView.vue`
  - any existing `.vue` file proven by the audit test to expose raw English enum/action values

**Interfaces:**
- Produces: label functions that return a Chinese label for known values and `未知状态` / `未知角色` / `未知操作` / `未知对象` for unknown values.
- Produces: `normalizeApiError` mappings for every backend `ErrorCode` used by current workflows.

- [ ] **Step 1: Write exhaustive label tests**

Build explicit cases:

```ts
expect(projectStatusLabel('ACTIVE')).toBe('进行中')
expect(projectStatusLabel('ARCHIVED')).toBe('已归档')
expect(roleLabel('OWNER')).toBe('所有者')
expect(taskStatusLabel('BLOCKED')).toBe('已阻塞')
expect(documentStatusLabel('INDEXING')).toBe('向量化中')
expect(auditActionLabel('TASK_PLAN_CONFIRMED')).toBe('确认 AI 任务规划')
expect(auditEntityLabel('PROJECT_DOCUMENT')).toBe('项目文档')
expect(auditActionLabel('UNRECOGNIZED')).toBe('未知操作')
```

Assert no unknown function returns its raw input.

- [ ] **Step 2: Add an ErrorCode parity test**

Read the backend enum text as a fixture or maintain an explicit current-code list in the test, then assert every user-facing code has an entry in `ERROR_CODE_MESSAGES`. Required missing mapping includes:

```ts
PROJECT_CONFIRMED_PLANS_EXIST: '项目中仍有已确认的 AI 规划，暂时不能删除'
```

- [ ] **Step 3: Run tests and confirm red**

Run:

```powershell
cd E:\ai-collab\ai-collab-frontend
pnpm test -- src/shared/display-labels.test.ts src/api
```

Expected: action/entity mismatches and missing error mapping fail.

- [ ] **Step 4: Correct mappings and visible product name**

Replace the primary `AI Collab` brand text with:

```html
<strong>高校竞赛 AI 项目协作平台</strong>
```

Change user-visible version copy from `v{{ plan.latestVersionNo }}` to `版本 {{ plan.latestVersionNo }}`. Technical model identifiers and file formats remain unchanged.

- [ ] **Step 5: Audit templates for raw enum rendering**

Run:

```powershell
Get-ChildItem src -Recurse -File -Include *.vue |
  Select-String -Pattern '\{\{[^}]*(status|role|priority|action|entityType)[^}]*\}\}'
```

For every result, confirm it passes through a label function or is a deliberate technical identifier.

- [ ] **Step 6: Verify frontend**

Run:

```powershell
pnpm test
pnpm typecheck
pnpm build
```

Expected: all commands exit 0.

- [ ] **Step 7: Commit**

```powershell
git add ai-collab-frontend/src/shared ai-collab-frontend/src/api/api-result.ts ai-collab-frontend/src/modules/planning/PlanningView.vue
git commit -m "fix(frontend): enforce safe Chinese user-facing copy"
```

---

### Task 7: Add a credential-safe local integration smoke runner

**Files:**
- Create: `scripts/smoke-existing.ps1`
- Create: `docs/development/smoke-testing.md`

**Interfaces:**
- Consumes: root `.env`, local backend at `http://localhost:8080`, allowed origin `http://localhost:5173`.
- Produces: exit code 0 only when authentication and all implemented read workflows return expected success; never prints secrets.

- [ ] **Step 1: Create a smoke script test mode**

The script parameters:

```powershell
param(
  [string]$BackendBaseUrl = 'http://localhost:8080',
  [string]$FrontendOrigin = 'http://localhost:5173',
  [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env')
)
```

It must:

1. read `DEMO_OWNER_USERNAME` and `DEMO_OWNER_PASSWORD` without echoing values;
2. create a `WebRequestSession`;
3. login with `Origin`;
4. retain the Access Token only in memory;
5. request `/auth/me`;
6. request `/projects`;
7. for the first available project request Dashboard, Audit, tasks, milestones, documents, knowledge sessions, and task plans;
8. validate `code == 'SUCCESS'`;
9. logout in `finally`;
10. print only endpoint name, HTTP result, business code, and item count;
11. exit nonzero on any failure.

- [ ] **Step 2: Verify failure behavior**

Run against an unused port:

```powershell
.\scripts\smoke-existing.ps1 -BackendBaseUrl http://localhost:65534
```

Expected: nonzero exit code, Chinese network failure summary, no credential values.

- [ ] **Step 3: Verify successful local behavior**

Start infrastructure/backend/frontend as documented, then run:

```powershell
.\scripts\smoke-existing.ps1
```

Expected: login, current user, project list, all implemented project read endpoints, and logout report success.

- [ ] **Step 4: Document destructive boundaries**

`docs/development/smoke-testing.md` must state that the smoke runner performs login/session creation and logout but does not create, update, upload, confirm, or delete project data.

- [ ] **Step 5: Commit**

```powershell
git add scripts/smoke-existing.ps1 docs/development/smoke-testing.md
git commit -m "test(integration): add credential-safe local smoke runner"
```

---

### Task 8: Build the evidence-backed feature matrix

**Files:**
- Create: `docs/feature-matrix.md`
- Modify: `docs/api/openapi.yaml`

**Interfaces:**
- Consumes: verified Controller endpoints, Vue routes/API modules, automated test reports, and smoke results.
- Produces: the authoritative status vocabulary `已完成 | 部分完成 | 未实现 | 不在第一版`.

- [ ] **Step 1: Inventory backend and frontend entries**

Collect:

```powershell
Get-ChildItem ai-collab-backend/src/main/java -Recurse -File -Include *Controller.java |
  Select-String -Pattern '@(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)'

Get-ChildItem ai-collab-frontend/src -Recurse -File -Include *.ts,*.vue |
  Select-String -Pattern 'httpClient\.(get|post|put|patch|delete)'
```

- [ ] **Step 2: Write the matrix with fixed columns**

Use:

```markdown
| 功能 | 状态 | 后端入口 | 前端入口 | 权限 | 自动化证据 | 手工验收 | 已知限制 |
```

Required rows include every item from the approved sixteen-section first-version description plus Dashboard, Audit, current registration/invitation behavior, model configuration, and Agent.

- [ ] **Step 3: Apply evidence rules**

Examples:

```markdown
| 项目更新与删除 | 已完成 | `PATCH/DELETE /api/v1/projects/{projectId}` | 我的项目页 | OWNER | 后端权限/乐观锁测试；ProjectListView 测试 | 编辑后刷新；确认删除 | 有文档或已确认规划时拒绝删除 |
| 项目类型 | 未实现 | 无 | 无 | — | 无 | — | 当前数据库没有该字段 |
| 项目四状态 | 部分完成 | 项目更新支持 `ACTIVE/ARCHIVED` | 项目编辑 | OWNER | 项目更新测试 | 切换归档状态 | 缺少准备中、已完成 |
| 知识问答流式输出 | 未实现 | 无流式端点 | 无 | MEMBER+ | 无 | — | 当前一次性返回答案 |
| AI 任务规划 | 已完成 | `/ai/task-plans` | AI 任务规划页 | ADMIN/OWNER；MEMBER 只读 | 规划测试套件 | 生成、编辑、确认 | 属于受控工作流，不是通用 Agent |
```

- [ ] **Step 4: Cross-check OpenAPI**

Compare every implemented Controller endpoint to `docs/api/openapi.yaml`. Add or correct only real implemented paths, request fields, response fields, statuses, and business codes. Do not document planned endpoints.

- [ ] **Step 5: Self-review**

Search:

```powershell
Select-String -Path docs/feature-matrix.md -Pattern '全部实现|均已完成|内容未填写|以后再写'
```

Expected: no unsupported completion claim or placeholder.

- [ ] **Step 6: Commit**

```powershell
git add docs/feature-matrix.md docs/api/openapi.yaml
git commit -m "docs: add evidence-backed implementation matrix"
```

---

### Task 9: Rebuild authoritative project and Claude/MiMo development documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/architecture.md`
- Modify: `docs/database.md`
- Modify: `docs/FUTURE_ROADMAP.md`
- Create: `docs/development/README.md`
- Create: `docs/development/backend-conventions.md`
- Create: `docs/development/frontend-conventions.md`
- Create: `docs/development/agent-design.md`
- Create: `docs/development/claude-mimo-task-template.md`
- Modify: `docs/learning/phase-01-access-token-auth.md`
- Modify: `docs/learning/phase-02-refresh-token-session.md`
- Modify: `docs/learning/phase-03-vue-auth-shell.md`
- Modify: `docs/learning/phase-04-project-member-module.md`
- Modify: `docs/learning/phase-05-account-work-management.md`
- Modify: `docs/learning/phase-06-document-knowledge-base.md`
- Modify: `docs/learning/phase-07-knowledge-qa.md`
- Modify: `docs/learning/phase-08-ai-task-planning.md`
- Delete: `docs/prompts/Phase09-Claude-MiMo-Prompts.md`

**Interfaces:**
- Consumes: `docs/feature-matrix.md`, verified code, OpenAPI, Flyway history, and smoke commands.
- Produces: one consistent documentation hierarchy for humans, Claude, and strictly constrained MiMo execution.

- [ ] **Step 1: Rewrite `CLAUDE.md` as a concise startup index**

Keep:

- product positioning;
- exact stack;
- verified current status with link to feature matrix;
- immutable architectural constraints;
- start/test commands;
- document reading order;
- current work rules on `main`.

Remove duplicated full feature prose and any unsupported “all complete” claim. Target 250–400 lines only if every line is operationally useful; prefer shorter.

- [ ] **Step 2: Correct architecture and database facts**

Architecture must describe:

- modular monolith;
- current `ACTIVE | ARCHIVED` project status;
- current non-streaming RAG;
- current two-stage controlled planning workflow;
- Phase 09 Dashboard/Audit authorization and aggregation;
- future Agent as unimplemented.

Database must describe migrations through V12 and not state V11 is latest.

- [ ] **Step 3: Rewrite the roadmap from matrix gaps**

Roadmap groups:

1. finish partial first-version features;
2. implement missing first-version features;
3. future Agent framework;
4. later enhancements.

Every roadmap item links back to the feature matrix status and must not appear in the “completed” section.

- [ ] **Step 4: Create concrete development conventions**

`backend-conventions.md` must include actual examples:

```java
@GetMapping("/{projectId}/resource/{resourceId}")
public ApiResponse<ResourceView> get(
        @PathVariable UUID projectId,
        @PathVariable UUID resourceId,
        @AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.success(service.get(projectId, resourceId, userId(jwt)));
}
```

```java
@Transactional(readOnly = true)
public ResourceView get(UUID projectId, UUID resourceId, UUID userId) {
    accessGuard.requireMember(projectId, userId);
    return repository.find(projectId, resourceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
}
```

`frontend-conventions.md` must include actual API/result/error patterns and centralized Chinese labels.

- [ ] **Step 5: Document future Agent architecture without implementing it**

`agent-design.md` must define:

- current planning workflow versus Agent;
- `AgentRun`, `AgentStep`, `AgentToolCall`, `AgentApproval` conceptual records;
- tool registry wrapping Application Services;
- project permission propagation;
- read tool versus write tool policy;
- human approval before high-impact writes;
- budgets, timeout, cancellation, idempotency, audit, and prompt-injection boundaries;
- phased implementation order;
- explicit prohibition on direct Mapper/database/MinIO/arbitrary URL access.

- [ ] **Step 6: Create the strict Claude/MiMo task template**

The template must require callers to fill:

```markdown
## 唯一目标
## 允许读取的文档
## 允许修改的文件
## 禁止修改的范围
## 已确认接口
## 必须先失败的测试
## 实现步骤
## 验证命令与期望结果
## 停止条件
## 最终输出格式
```

It must explicitly forbid MiMo from adding endpoints, tables, migrations, states, roles, dependencies, or fallback behavior unless they are written in the task.

- [ ] **Step 7: Mark learning documents as historical**

Add the same banner immediately below each title:

```markdown
> 本文是对应阶段的学习记录，不是当前功能完成状态的权威来源。
> 当前状态以 `docs/feature-matrix.md`、`docs/api/openapi.yaml` 和代码测试为准。
```

Do not rewrite historical teaching explanations unless they state a current fact that is unsafe or false.

- [ ] **Step 8: Migrate and delete the Phase 09 temporary prompt**

Before deletion, verify its useful constraints exist in the feature matrix, development conventions, or task template:

```powershell
Select-String -Path docs/development/*.md,docs/feature-matrix.md -Pattern 'Dashboard|Audit|project_id|MiMo|验证命令'
```

Then delete `docs/prompts/Phase09-Claude-MiMo-Prompts.md`. Preserve the approved Phase 09 design spec and implementation plan as historical records.

- [ ] **Step 9: Documentation consistency scan**

Run:

```powershell
Get-ChildItem CLAUDE.md,docs -Recurse -File -Include *.md |
  Select-String -Pattern 'Phase 09 完成|当前实现里程碑为 Phase 08|第一版.*全部实现|最新.*V11|当前.*V11'
```

Expected: no contradictory current-state claims. Historical statements must be clearly labeled as historical.

- [ ] **Step 10: Commit**

```powershell
git add CLAUDE.md docs
git commit -m "docs: rebuild verified development guidance"
```

---

### Task 10: Full verification and final stabilization audit

**Files:**
- Modify: `docs/feature-matrix.md` when final evidence changes a status.

Any code or contract failure found in this task returns execution to the task that owns that file. Fix and verify it there before restarting Task 10; do not make unplanned mixed corrections inside the final audit.

**Interfaces:**
- Consumes: all tasks above.
- Produces: fresh evidence for build, test, integration, documentation, and scope-compliance claims.

- [ ] **Step 1: Run full backend verification**

```powershell
cd E:\ai-collab\ai-collab-backend
.\mvnw.cmd clean test
.\mvnw.cmd -DskipTests package
```

Expected: both commands exit 0 with zero test failures.

- [ ] **Step 2: Run full frontend verification**

```powershell
cd E:\ai-collab\ai-collab-frontend
pnpm test
pnpm typecheck
pnpm build
```

Expected: every command exits 0. Record test file and test counts.

- [ ] **Step 3: Run local smoke verification**

```powershell
cd E:\ai-collab
.\scripts\smoke-existing.ps1
```

Expected: authentication, current user, projects, Dashboard, Audit, tasks, milestones, documents, knowledge sessions, planning list, and logout succeed without secret output.

- [ ] **Step 4: Run contract and documentation scans**

```powershell
git diff --check

Get-ChildItem ai-collab-frontend/src -Recurse -File -Include *.vue |
  Select-String -Pattern '\{\{[^}]*(status|role|priority|action|entityType)[^}]*\}\}'

Get-ChildItem CLAUDE.md,docs -Recurse -File -Include *.md |
  Select-String -Pattern '第一版.*全部实现|当前实现里程碑为 Phase 08|最新.*V11'
```

Expected: no whitespace errors, no unapproved raw enum display, and no contradictory current-state claims.

- [ ] **Step 5: Audit the final diff against the approved exclusions**

Run:

```powershell
git diff --name-status 3c38b27..HEAD
git status --short
```

Confirm no implementation of project type, four-state lifecycle, self-leave, owner transfer, administrator account creation, RAG streaming, answer feedback, notifications, reports, Gantt, or Agent runtime entered the diff.

- [ ] **Step 6: Correct evidence-backed statuses**

For each `已完成` row in `docs/feature-matrix.md`, identify a backend endpoint, frontend entry, permission rule, and verification result. Downgrade any row lacking this evidence to `部分完成`.

- [ ] **Step 7: Commit evidence-status corrections if any**

If Step 6 changes the matrix:

```powershell
git add docs/feature-matrix.md
git commit -m "docs: align feature status with final evidence"
```

Skip the commit when the matrix does not change.

- [ ] **Step 8: Final report**

Report:

- exact commits;
- backend and frontend test counts;
- build and smoke results;
- fixed defects;
- documents added/changed/deleted;
- functions still `部分完成` or `未实现`;
- known non-failing build warnings;
- confirmation that pre-existing user changes were preserved.

---

## Plan Self-Review

### Spec coverage

- Migration failure: Task 1.
- Audit action/detail defects: Task 2.
- Phase 09 backend completion: Task 3.
- Phase 09 frontend completion: Task 4.
- Backend-only project update/delete closure: Task 5.
- Chinese interface and safe errors: Task 6.
- Real local integration: Task 7.
- Accurate feature matrix and OpenAPI: Task 8.
- Project, Claude/MiMo, and Agent documentation: Task 9.
- Fresh full verification and scope audit: Task 10.

### Type and contract consistency

- Project status remains exactly `ACTIVE | ARCHIVED`.
- Project update always carries integer `version`.
- Audit uses actual emitted codes such as `PROJECT_MEMBER_ROLE_CHANGED`, `PROJECT_DOCUMENT`, and `TASK_PLAN_CONFIRMED`.
- Dashboard remains member-readable; full Audit remains OWNER/ADMIN-only.
- Agent remains documentation-only in this implementation plan.

### Scope consistency

The plan only completes code that already exists in at least one executable layer, fixes demonstrated failures, verifies existing workflows, and restructures documentation. Completely absent product capabilities are recorded but not implemented.
