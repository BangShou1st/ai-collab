# Project Collaboration Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a persistent, budgeted project collaboration Agent with sourced analysis, approval-gated task and milestone writes, schedules and notifications, evaluation templates, and bounded supervisor-specialist delegation.

**Architecture:** Add an `agent` package-by-feature module to the existing Spring Boot modular monolith. Persist every run transition and step in PostgreSQL, drive the model through a strict JSON decision protocol, expose only allow-listed adapters over existing application services, and keep all business writes behind nonce-bound human approvals. Add one Vue project workspace that consumes the scoped Agent APIs and renders runs, citations, approvals, schedules, evaluations, and specialist steps.

**Tech Stack:** Java 21, Spring Boot 4.1, MyBatis-Plus annotation SQL, PostgreSQL/Flyway, Jackson, Vue 3, TypeScript 5.8, Element Plus, Pinia/Axios, Vitest.

## Global Constraints

- Phase 1 has no business-write tool.
- Phase 2 registers only narrow read/analysis tools.
- Never register arbitrary SQL, arbitrary HTTP/URL, deletion, member/role/ownership, secret/model configuration, planning confirmation, or automatic approval.
- Model decisions use strict JSON over the existing OpenAI-compatible gateway; native `tool_calls` are not required.
- Provider configuration remains server-side environment configuration.
- Scheduled runs may create proposals but never resolve approvals.
- Specialists are read-only, depth is exactly one, and the whole run tree shares one budget.
- All project-scoped reads use `project_id + entity_id`; controllers never call mappers.
- External model calls run outside database transactions.
- All user-facing frontend text is Simplified Chinese.
- Existing dirty-worktree changes are preserved; only explicit Agent files and minimal integration edits are staged.

---

## File Map

### Backend domain and configuration

- `agent/domain/model/AgentRunStatus.java`: legal run states.
- `agent/domain/model/AgentStepType.java`: immutable step types.
- `agent/domain/model/AgentDecision.java`: sealed `call_tool`, `delegate`, and `final` decisions.
- `agent/domain/model/AgentBudget.java`: immutable limits and usage.
- `agent/domain/policy/AgentStateMachine.java`: legal transition guard.
- `agent/domain/policy/AgentBudgetPolicy.java`: parent/child budget checks.
- `agent/domain/policy/AgentToolPolicy.java`: phase, role, schedule, and approval gates.
- `agent/application/AgentProperties.java`: bounded configuration, including JSON-mode capability.

### Backend persistence

- `db/migration/V22__add_agent_runtime.sql`: sessions, runs, steps, messages, approvals, schedules, fires, evaluations, constraints, and indexes.
- `agent/infrastructure/entity/*`: persistence rows.
- `agent/infrastructure/mapper/*`: annotation SQL with project scoping.
- `agent/infrastructure/repository/AgentRepository.java`: transactional persistence facade.

### Backend runtime and tools

- `agent/application/AgentDecisionParser.java`: strict JSON parsing and validation.
- `agent/application/AgentPromptFactory.java`: trusted instructions and untrusted context boundaries.
- `agent/application/AgentRunService.java`: session/run commands and queries.
- `agent/application/AgentWorker.java`: one committed decision unit per claim.
- `agent/application/AgentRecoveryJob.java`: queued/retry/expired-lease recovery.
- `agent/domain/tool/AgentTool.java`: narrow tool interface.
- `agent/domain/tool/AgentToolResult.java`: bounded JSON payload with sources/inferences.
- `agent/infrastructure/tool/*`: adapters over project/work/knowledge/audit services.
- `agent/infrastructure/tool/AgentToolRegistry.java`: immutable allow-list.

### Backend approvals, schedules, evaluation, delegation

- `agent/application/AgentApprovalService.java`: propose, approve, reject, nonce, idempotency, version handling.
- `agent/infrastructure/tool/TaskWriteAgentTool.java`: approved create/update calls.
- `agent/infrastructure/tool/MilestoneWriteAgentTool.java`: approved create/update calls.
- `agent/application/AgentScheduleService.java`: daily/weekly commands and next-fire calculation.
- `agent/application/AgentScheduleJob.java`: idempotent due-schedule claiming.
- `agent/application/AgentNotificationService.java`: stable notification dedupe keys.
- `agent/application/AgentDelegationService.java`: fixed read-only specialist roles.
- `agent/application/AgentEvaluationService.java`: fixture evaluation and metrics.

### Backend HTTP

- `agent/api/controller/AgentSessionController.java`
- `agent/api/controller/AgentApprovalController.java`
- `agent/api/controller/AgentScheduleController.java`
- `agent/api/controller/AgentEvaluationController.java`
- `agent/api/dto/*`
- `agent/application/view/*`
- `common/exception/ErrorCode.java`: Agent-specific stable errors.

### Frontend

- `modules/agent/types.ts`: exact API contracts and enums.
- `modules/agent/agent-api.ts`: project-scoped API client.
- `modules/agent/AgentView.vue`: session, message, run, and tabs shell.
- `modules/agent/components/AgentTimeline.vue`
- `modules/agent/components/AgentSources.vue`
- `modules/agent/components/AgentApprovalCard.vue`
- `modules/agent/components/AgentSchedulePanel.vue`
- `modules/agent/components/AgentEvaluationPanel.vue`
- `modules/agent/components/AgentSpecialistSteps.vue`
- `router.ts`, `shared/AppShell.vue`, `shared/display-labels.ts`: minimal navigation and label integration.

### Contracts and documentation

- `docs/api/openapi.yaml`
- `docs/database.md`
- `docs/architecture.md`
- `docs/feature-matrix.md`

---

### Task 1: Runtime schema, state machine, budget, and strict decision protocol

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V22__add_agent_runtime.sql`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentRunStatus.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentStepType.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentDecision.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentBudget.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentStateMachine.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/policy/AgentBudgetPolicy.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentDecisionParser.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/domain/AgentRuntimeDomainTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `AgentDecision parse(String json, boolean correctionAttempted)`.
- Produces: `void requireTransition(AgentRunStatus from, AgentRunStatus to)`.
- Produces: `AgentBudget debitStep()`, `debitTool()`, `debitTokens(int input, int output, boolean estimated)`, and `debitChild()`.

- [ ] **Step 1: Write failing domain tests**

```java
@Test void rejectsUnknownDecisionFields() {
    assertThatThrownBy(() -> parser.parse(
        "{\"action\":\"final\",\"answer\":\"ok\",\"citations\":[],\"inferences\":[],\"extra\":1}", false))
        .isInstanceOf(AgentDecisionException.class);
}

@Test void budgetStopsAtHardLimit() {
    AgentBudget exhausted = AgentBudget.defaults().withUsage(12, 0, 0, 0, 0, false);
    assertThatThrownBy(exhausted::debitStep).isInstanceOf(AgentBudgetExceededException.class);
}

@Test void waitingApprovalCanOnlyResumeThroughQueue() {
    assertThatCode(() -> states.requireTransition(WAITING_FOR_APPROVAL, QUEUED)).doesNotThrowAnyException();
    assertThatThrownBy(() -> states.requireTransition(WAITING_FOR_APPROVAL, RUNNING))
        .isInstanceOf(IllegalAgentTransitionException.class);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentRuntimeDomainTest test`

Expected: compilation failure because Agent runtime types do not exist.

- [ ] **Step 3: Implement the minimal domain and parser**

Use a Jackson reader configured with `FAIL_ON_UNKNOWN_PROPERTIES`, sealed decision records, explicit maximum answer/reason/object sizes, and exact action dispatch. Keep state and budget policies pure.

- [ ] **Step 4: Run domain tests and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentRuntimeDomainTest test`

Expected: all Agent runtime domain tests pass.

- [ ] **Step 5: Write and verify migration integration tests**

Assert V22 creates all ten tables, `(run_id, sequence_no)`, `(schedule_id, scheduled_for)`, approval idempotency, parent depth, non-negative budgets, and state check constraints.

Run: `.\mvnw.cmd -Dtest=AgentMigrationIntegrationTest test`

Expected before migration: FAIL because tables are absent. Expected after migration: PASS.

- [ ] **Step 6: Commit**

```powershell
git add ai-collab-backend/src/main/resources/db/migration/V22__add_agent_runtime.sql
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentDecisionParser.java
git commit -m "feat(agent): add persistent runtime domain"
```

### Task 2: Project-scoped persistence, sessions, runs, and recovery claims

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/entity/*.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/mapper/AgentSessionMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/mapper/AgentRunMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/mapper/AgentApprovalMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/mapper/AgentScheduleMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/mapper/AgentEvaluationMapper.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRecoveryJob.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/controller/AgentSessionController.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/dto/*.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/*.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentRepositoryIntegrationTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentRunServiceTest.java`

**Interfaces:**
- Produces: `AgentSessionView createSession(UUID projectId, UUID userId, CreateAgentSessionRequest request)`.
- Produces: `AgentRunView submit(UUID projectId, UUID sessionId, UUID userId, SubmitAgentMessageRequest request)`.
- Produces: `Optional<ClaimedAgentRun> claimNext(OffsetDateTime now, Duration lease)`.
- Produces: `void appendStep(UUID projectId, UUID runId, int expectedVersion, NewAgentStep step, AgentRunStatus nextStatus)`.

- [ ] **Step 1: Write failing isolation and claim tests**

Test that a user cannot read another project's session by global ID, two workers cannot claim the same run, an expired lease can be reclaimed, and append uses `(projectId, runId, version)`.

- [ ] **Step 2: Run tests and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentRepositoryIntegrationTest,AgentRunServiceTest test`

Expected: FAIL because repository and services are absent.

- [ ] **Step 3: Implement scoped mappers and application APIs**

All entity reads use both project and resource IDs. `submit` stores the user message and queues a default-budget run in one short transaction. List APIs enforce bounded pagination. Cancel and retry go through the state machine.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentRepositoryIntegrationTest,AgentRunServiceTest test`

- [ ] **Step 5: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git commit -m "feat(agent): persist sessions runs and recovery claims"
```

### Task 3: Read-only tool registry and one-step model worker

**Files:**
- Create: `agent/domain/tool/AgentTool.java`
- Create: `agent/domain/tool/AgentToolResult.java`
- Create: `agent/domain/policy/AgentToolPolicy.java`
- Create: `agent/application/AgentProperties.java`
- Create: `agent/application/AgentPromptFactory.java`
- Create: `agent/application/AgentWorker.java`
- Create: `agent/infrastructure/tool/AgentToolRegistry.java`
- Create: `agent/infrastructure/tool/ProjectOverviewAgentTool.java`
- Create: `agent/infrastructure/tool/TaskQueryAgentTool.java`
- Create: `agent/infrastructure/tool/MilestoneQueryAgentTool.java`
- Create: `agent/infrastructure/tool/KnowledgeSearchAgentTool.java`
- Create: `agent/infrastructure/tool/DashboardAgentTool.java`
- Create: `agent/infrastructure/tool/AuditSummaryAgentTool.java`
- Modify: `infrastructure/ai/ChatModelProperties.java`
- Modify: `infrastructure/ai/OpenAiCompatibleChatModelGateway.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentWorkerTest.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentToolRegistryTest.java`
- Test: `src/test/java/com/shitulelv/aicollab/infrastructure/ai/OpenAiCompatibleChatModelGatewayTest.java`

**Interfaces:**
- `AgentToolResult execute(AgentToolContext context, JsonNode arguments)`.
- `Optional<AgentTool> find(String name)`.
- `void executeOne(UUID runId)` commits exactly one model or tool decision unit.

- [ ] **Step 1: Write failing registry and worker tests**

Assert the phase-one registry contains only seven read tools, rejects unknown/cross-project IDs, truncates list/text output, treats context as untrusted, and stops before a model call when budget is exhausted.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentWorkerTest,AgentToolRegistryTest,OpenAiCompatibleChatModelGatewayTest test`

- [ ] **Step 3: Implement JSON-mode capability**

Add `chat.json-mode-enabled` (default `true`). When false, omit `response_format`; do not weaken local strict parsing.

- [ ] **Step 4: Implement the immutable read-only registry and worker**

The worker claims a run, rebuilds bounded context, reserves budget, performs the model call outside a transaction, parses the decision, and commits one step. A write-like or unknown tool decision becomes a policy error, never reflection or dynamic HTTP.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentWorkerTest,AgentToolRegistryTest,OpenAiCompatibleChatModelGatewayTest test`

- [ ] **Step 6: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai
git add ai-collab-backend/src/main/resources/application*.yml
git commit -m "feat(agent): run bounded read-only tool loop"
```

### Task 4: Sourced project Q&A, progress checks, risks, and weekly drafts

**Files:**
- Create: `agent/infrastructure/tool/ProjectQuestionAgentTool.java`
- Create: `agent/infrastructure/tool/ProjectProgressAgentTool.java`
- Create: `agent/infrastructure/tool/ProjectRiskAgentTool.java`
- Create: `agent/infrastructure/tool/WeeklyReportDraftAgentTool.java`
- Create: `agent/domain/model/AgentCitation.java`
- Create: `agent/domain/model/AgentInference.java`
- Modify: `agent/application/AgentPromptFactory.java`
- Modify: `agent/application/AgentWorker.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentSourcedAnswerTest.java`

**Interfaces:**
- Tool results expose `facts`, `citations`, `inferences`, and bounded `data`.
- Final messages persist citations separately from inferences.

- [ ] **Step 1: Write failing sourced-answer tests**

Assert knowledge facts require document/chunk/page identifiers, progress facts come from `ProjectDashboardQueryService`, risk and weekly metrics come from `WorkReportService`, unsupported factual claims are not promoted to citations, and source-less answers are marked as inference.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentSourcedAnswerTest test`

- [ ] **Step 3: Implement analysis adapters and final-answer validation**

Reuse existing deterministic services. Do not duplicate metric SQL. Sanitize and cap source quotes. Persist final answer, citations, and inferences in a single short transaction.

- [ ] **Step 4: Run and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentSourcedAnswerTest test`

- [ ] **Step 5: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git commit -m "feat(agent): add sourced project analysis"
```

### Task 5: Approval-gated task and milestone writes

**Files:**
- Create: `agent/application/AgentApprovalService.java`
- Create: `agent/domain/policy/AgentApprovalPolicy.java`
- Create: `agent/infrastructure/tool/TaskWriteAgentTool.java`
- Create: `agent/infrastructure/tool/MilestoneWriteAgentTool.java`
- Create: `agent/api/controller/AgentApprovalController.java`
- Create: `agent/api/dto/ResolveAgentApprovalRequest.java`
- Modify: `agent/application/AgentWorker.java`
- Modify: `agent/infrastructure/tool/AgentToolRegistry.java`
- Modify: `common/exception/ErrorCode.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentApprovalServiceTest.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentApprovalPostgresIntegrationTest.java`

**Interfaces:**
- `AgentApprovalView approve(UUID projectId, UUID approvalId, UUID approverId, String nonce, String idempotencyKey)`.
- `AgentApprovalView reject(UUID projectId, UUID approvalId, UUID approverId, RejectAgentApprovalRequest request)`.
- Write adapters accept only normalized DTOs and call `TaskApplicationService` or `MilestoneApplicationService`.

- [ ] **Step 1: Write failing approval security tests**

Cover no pre-approval business write, hashed one-use nonce, expiry, cross-project denial, current-role recheck, expected resource version, concurrent approval, idempotent retry, scheduled-run non-approval, and permanent absence of delete/member/planning tools.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentApprovalServiceTest,AgentApprovalPostgresIntegrationTest test`

- [ ] **Step 3: Implement proposal persistence and resolution**

Normalize whitelisted create/update fields, compute a user-readable diff, store nonce hash only, set run to `WAITING_FOR_APPROVAL`, then revalidate and call the existing service only from `approve`.

- [ ] **Step 4: Run and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentApprovalServiceTest,AgentApprovalPostgresIntegrationTest test`

- [ ] **Step 5: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java
git commit -m "feat(agent): gate work changes behind approval"
```

### Task 6: Daily/weekly schedules and idempotent notifications

**Files:**
- Create: `agent/domain/model/AgentScheduleRule.java`
- Create: `agent/application/AgentScheduleService.java`
- Create: `agent/application/AgentScheduleJob.java`
- Create: `agent/application/AgentNotificationService.java`
- Create: `agent/api/controller/AgentScheduleController.java`
- Create: `agent/api/dto/CreateAgentScheduleRequest.java`
- Create: `agent/api/dto/UpdateAgentScheduleRequest.java`
- Modify: `notification/application/service/NotificationApplicationService.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentScheduleServiceTest.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentScheduleIntegrationTest.java`

**Interfaces:**
- `OffsetDateTime nextFire(AgentScheduleRule rule, ZoneId zone, OffsetDateTime after)`.
- `void fireDueSchedules(OffsetDateTime now)`.
- `void createDeduplicated(..., String dedupeKey)` added to notification service.

- [ ] **Step 1: Write failing schedule tests**

Cover daily/weekly calculation, `Asia/Shanghai`, a DST zone, invalid zone, one fire per `(scheduleId, scheduledFor)`, permission loss disables a schedule, scheduled runs cannot approve, and completion/failure/proposal notifications deduplicate.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentScheduleServiceTest,AgentScheduleIntegrationTest test`

- [ ] **Step 3: Implement schedule commands and job**

Use persisted UTC next-fire values and IANA zones. Claim due rows in short transactions, create a unique fire, queue a run as the creator, and calculate the following fire. Notification failure is retried independently and never rolls back a completed run.

- [ ] **Step 4: Run and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentScheduleServiceTest,AgentScheduleIntegrationTest test`

- [ ] **Step 5: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/notification
git commit -m "feat(agent): add scheduled runs and notifications"
```

### Task 7: Evaluation templates and bounded supervisor-specialist runs

**Files:**
- Create: `agent/domain/model/AgentSpecialistRole.java`
- Create: `agent/application/AgentDelegationService.java`
- Create: `agent/application/AgentEvaluationService.java`
- Create: `agent/application/AgentEvaluationFixtureGateway.java`
- Create: `agent/api/controller/AgentEvaluationController.java`
- Modify: `agent/application/AgentWorker.java`
- Modify: `agent/infrastructure/tool/AgentToolRegistry.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentDelegationServiceTest.java`
- Test: `src/test/java/com/shitulelv/aicollab/agent/application/AgentEvaluationServiceTest.java`

**Interfaces:**
- `AgentRunView delegate(UUID parentRunId, AgentSpecialistRole role, String objective)`.
- `AgentEvaluationRunView runTemplate(UUID projectId, UUID templateId, UUID userId, EvaluationMode mode)`.
- Metrics: pass rate, tool-choice accuracy, citation coverage, budget compliance, approval-bypass count.

- [ ] **Step 1: Write failing delegation and evaluation tests**

Assert only three fixed roles, maximum three children, depth one, read-only role registries, shared atomic budget, no child approval, fixture-only default evaluation, and an approval-bypass attempt increments the metric and fails the case.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentDelegationServiceTest,AgentEvaluationServiceTest test`

- [ ] **Step 3: Implement delegation and versioned built-in templates**

Seed five templates through V22 data inserts or deterministic application initialization. Fixture mode supplies scripted decisions and fake tool results; real-model mode is admin-only, explicitly test-project-only, and read-only.

- [ ] **Step 4: Run and verify GREEN**

Run: `.\mvnw.cmd -Dtest=AgentDelegationServiceTest,AgentEvaluationServiceTest test`

- [ ] **Step 5: Commit**

```powershell
git add ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent
git add ai-collab-backend/src/main/resources/db/migration/V22__add_agent_runtime.sql
git commit -m "feat(agent): add evaluations and bounded specialists"
```

### Task 8: Agent frontend workspace and approval UI

**Files:**
- Create: `ai-collab-frontend/src/modules/agent/types.ts`
- Create: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Create: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Create: `ai-collab-frontend/src/modules/agent/components/*.vue`
- Create: `ai-collab-frontend/src/modules/agent/agent-api.test.ts`
- Create: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`
- Modify: `ai-collab-frontend/src/router.ts`
- Modify: `ai-collab-frontend/src/shared/AppShell.vue`
- Modify: `ai-collab-frontend/src/shared/display-labels.ts`
- Modify: `ai-collab-frontend/src/shared/display-labels.test.ts`

**Interfaces:**
- `agentApi` exposes scoped session/run/approval/schedule/evaluation methods returning `ApiResult<T>`.
- `AgentView` gets `projectId` from the route and current role from `project-context-store`.

- [ ] **Step 1: Write failing API and view tests**

Assert exact scoped URLs and verbs, idempotency header and nonce payload, Chinese states, unknown-value masking, fact/inference separation, approval diff, disabled duplicate submits, daily/weekly form, and specialist timeline rendering.

- [ ] **Step 2: Run and verify RED**

Run: `pnpm test -- src/modules/agent/agent-api.test.ts src/modules/agent/AgentView.test.ts src/shared/display-labels.test.ts`

- [ ] **Step 3: Implement types, API client, components, route, and navigation**

Use `httpClient`, `ApiResult`, `normalizeApiError`, safe plain text rendering, bounded polling only while a run is active, and abort polling on route/unmount. Admin-only controls are hidden for members but backend errors remain handled.

- [ ] **Step 4: Run and verify GREEN**

Run: `pnpm test -- src/modules/agent/agent-api.test.ts src/modules/agent/AgentView.test.ts src/shared/display-labels.test.ts`

- [ ] **Step 5: Run frontend type and build checks**

Run: `pnpm typecheck`

Run: `pnpm build`

- [ ] **Step 6: Commit**

```powershell
git add ai-collab-frontend/src/modules/agent
git add ai-collab-frontend/src/router.ts
git add ai-collab-frontend/src/shared/AppShell.vue
git add ai-collab-frontend/src/shared/display-labels.ts
git commit -m "feat(agent): add collaboration workspace"
```

### Task 9: OpenAPI, architecture, database, feature matrix, and full verification

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database.md`
- Modify: `docs/architecture.md`
- Modify: `docs/feature-matrix.md`
- Create: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/api/AgentOpenApiContractTest.java`

**Interfaces:**
- OpenAPI schemas exactly match Java Views and TypeScript types.
- Feature matrix records each delivered phase from verification evidence.

- [ ] **Step 1: Write failing OpenAPI contract test**

Assert all scoped Agent paths, approval nonce/idempotency contract, state enums, schedule enum, evaluation schemas, and absence of forbidden delete/member/key/planning-confirm Agent paths.

- [ ] **Step 2: Run and verify RED**

Run: `.\mvnw.cmd -Dtest=AgentOpenApiContractTest test`

- [ ] **Step 3: Update OpenAPI and documentation**

Document V22 tables, runtime flow, approval boundary, model compatibility flag, schedule semantics, specialist limits, evaluation metrics, and exact feature status.

- [ ] **Step 4: Run contract and repository validators**

Run: `.\mvnw.cmd -Dtest=AgentOpenApiContractTest test`

Run: `.\scripts\validate-openapi.ps1`

- [ ] **Step 5: Run complete backend verification**

Run: `Set-Location ai-collab-backend; .\mvnw.cmd test`

Run: `.\mvnw.cmd clean package -DskipTests`

- [ ] **Step 6: Run complete frontend verification**

Run: `Set-Location ..\ai-collab-frontend; pnpm test`

Run: `pnpm typecheck`

Run: `pnpm build`

- [ ] **Step 7: Verify diff hygiene and requirements**

Run: `Set-Location ..; git diff --check`

Inspect `git status --short`, confirm unrelated dirty-worktree files remain untouched, and map every design section to code/tests/docs.

- [ ] **Step 8: Commit documentation**

```powershell
git add docs/api/openapi.yaml docs/database.md docs/architecture.md docs/feature-matrix.md
git commit -m "docs: document project collaboration agent"
```
