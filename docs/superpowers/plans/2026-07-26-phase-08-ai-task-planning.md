# Phase 08 AI Task Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete project-scoped AI task-planning workflow from asynchronous two-stage generation through immutable review and idempotent transactional confirmation.

**Architecture:** Add an isolated `planning` backend module with a single draft validator, JDBC persistence, an asynchronous generation orchestrator, and separate command/query/confirmation services. V5 replaces the unused V1 placeholder planning tables without changing V1–V4, then adds immutable versions, attempts, confirmations, and nullable provenance columns to the existing work tables. The Vue module uses one polling controller, an explicit working-copy editor, persisted confirmation keys, and server-computed permissions.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring JDBC/MyBatis, PostgreSQL/Flyway, JUnit 5; Vue 3, TypeScript, Element Plus, Axios, Vitest.

## Global Constraints

- The design in `docs/superpowers/specs/2026-07-26-phase-08-ai-task-planning-design.md` is authoritative.
- Work only on `feat/phase-08-ai-task-planning`; do not push, merge, edit V1–V4, expose secrets, or commit build output.
- OWNER/ADMIN can write, MEMBER is read-only, and non-members receive `404 PROJECT_NOT_FOUND`.
- Versions are insert-only; saves require the latest `baseVersionId`; confirmation requires a UUID `Idempotency-Key`.
- Generation has exactly two stages and at most one repair per stage; model calls never run in a database transaction.
- Confirmation creates only new milestones/tasks/dependencies in one transaction; Redis is never the idempotency source of truth.

---

### Task 1: Preserve the approved specification and establish the executable contract

**Files:**
- Create: `docs/superpowers/specs/2026-07-26-phase-08-ai-task-planning-design.md`
- Create: `docs/superpowers/plans/2026-07-26-phase-08-ai-task-planning.md`

**Interfaces:**
- Consumes: the user-supplied UTF-8 design document and current repository paths.
- Produces: the authoritative checked-in spec and this task-by-task execution contract.

- [ ] Copy the supplied specification byte-for-byte and verify its SHA-256 matches the download.
- [ ] Review this plan against state, cancellation, idempotency, rollback, authorization, and frontend safety requirements.
- [ ] Run `git diff --check`; expect exit 0.
- [ ] Commit with `docs: add Phase 08 design and implementation plan`.

### Task 2: Replace the unused placeholder schema with the Phase 08 persistence model

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V5__create_ai_task_planning.sql`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/work/infrastructure/entity/MilestoneEntity.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/work/infrastructure/entity/TaskEntity.java`

**Interfaces:**
- Consumes: V1 table names `project`, `project_member`, `milestone`, `project_task`, `task_dependency`, `project_document`, and `app_user`.
- Produces: `ai_task_plan`, `ai_task_plan_version`, `ai_task_plan_attempt`, `ai_task_plan_confirmation`, plus nullable source-plan fields.

- [ ] Add a migration contract test that applies V1–V5 to PostgreSQL when the integration profile is available and asserts unique/check/delete behavior.
- [ ] Run the migration test and confirm RED because V5 does not exist.
- [ ] Add V5 with all foreign keys, checks, unique constraints, indexes, and non-cascading provenance references.
- [ ] Run backend tests and Flyway validation; expect PASS.
- [ ] Run `git diff --check`; commit `feat(db): add AI task planning schema`.

### Task 3: Implement domain types, validation, prompt safety, configuration, and request hashing

**Files:**
- Create focused files under `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/`
- Create planning configuration files under `.../planning/infrastructure/ai/`
- Create tests under `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/`

**Interfaces:**
- Produces: `TaskPlanDraftValidator.validate(ValidationContext, TaskPlanDraft) -> ValidationResult`, `TaskPlanState.canTransitionTo`, `PlanningPromptText.escapeBoundary`, `PlanningRequestHash.confirmation`, and `ResolvedPlanningModelProperties`.

- [ ] Write failing table-driven tests for date/size/tempKey/member/source/dependency/DAG/warning rules and skeleton preservation.
- [ ] Write failing tests for state transitions, one-repair policy, Unicode code-point budgeting, prompt boundary escaping, property fallback/disable, and canonical confirmation hashes.
- [ ] Run targeted tests and confirm each fails for a missing production type or behavior.
- [ ] Implement the smallest focused records/enums/services that satisfy the tests.
- [ ] Run targeted and full backend tests; expect PASS.
- [ ] Run `git diff --check`; commit `feat(planning): add planning domain validation and configuration`.

### Task 4: Implement persistence, project-scoped query/command APIs, immutable versions, cancellation, and recovery

**Files:**
- Create planning entities/repository under `.../planning/infrastructure/`
- Create DTOs/controller under `.../planning/api/`
- Create `TaskPlanCommandService`, `TaskPlanQueryService`, and `TaskPlanRecoveryJob` under `.../planning/application/`
- Modify common error code/handler mappings and application configuration.

**Interfaces:**
- Produces all list/create/detail/cancel/retry/regenerate/version/restore/delete endpoints from the spec.
- Repository locks a plan before version allocation or state mutation and scopes every lookup by project.

- [ ] Add failing service/controller tests for OWNER/ADMIN, MEMBER 403, non-member 404, cross-project IDs, immutable saves, restore, delete, and state conflicts.
- [ ] Confirm RED, then implement JDBC repository, services, permissions, and HTTP mappings.
- [ ] Add cancellation generation-sequence invalidation and stale-process recovery tests; confirm RED then GREEN.
- [ ] Run backend tests and package; expect PASS.
- [ ] Run `git diff --check`; commit `feat(planning): add task plan APIs and immutable lifecycle`.

### Task 5: Implement two-stage generation, source snapshotting, repair, rate limiting, and late-result rejection

**Files:**
- Create `TaskPlanGenerationOrchestrator`, `TaskPlanContextAssembler`, `TaskPlanModelClient`, and `TaskPlanOutputParser`.
- Create planning executor/rate-limit configuration and orchestration tests.

**Interfaces:**
- The orchestrator accepts `(planId, generationSeq, attemptId)` and writes only if all three still match an allowed state.
- The model client returns structured skeleton/detail output; the validator remains the sole full-domain validation entry.

- [ ] Add controllable-executor tests for success, repair success, second failure, detail-only retry, cancellation, stale attempts, queue rejection, timeout/429/503, and skeleton mutation.
- [ ] Confirm RED without sleeps.
- [ ] Implement snapshot construction (selected READY documents only, hash dedupe, S1–S12, 16000 code points), prompt boundaries, two-stage attempts, one repair, AI-call logging, and late-result discard.
- [ ] Run orchestration and full backend tests; expect PASS.
- [ ] Run `git diff --check`; commit `feat(planning): implement two-stage planning generation`.

### Task 6: Implement idempotent, transactional confirmation

**Files:**
- Create `TaskPlanConfirmationService` and focused transaction collaborators.
- Extend milestone/task mappers for source-plan creation.
- Add confirmation transaction and concurrency tests.

**Interfaces:**
- `confirm(projectId, planId, versionId, idempotencyKey, actorId)` canonicalizes the request, claims a database confirmation, and lands formal work in one transaction.

- [ ] Add failing tests for same-key replay, key reuse conflict, already-confirmed replay, concurrent confirm, member revalidation, and four injected rollback points.
- [ ] Confirm RED.
- [ ] Implement the short claim transaction, atomic landing transaction, and independent failure-finalization transaction.
- [ ] Verify no partial milestone/task/dependency rows survive any injected failure.
- [ ] Run backend tests/package; expect PASS.
- [ ] Run `git diff --check`; commit `feat(planning): add idempotent transactional confirmation`.

### Task 7: Implement the Vue workflow and task-board provenance

**Files:**
- Create `ai-collab-frontend/src/modules/planning/types.ts`
- Create `.../planning/planning-api.ts`
- Create `.../planning/planning-poller.ts`
- Create `.../planning/planning-draft.ts`
- Create `.../planning/PlanningView.vue`
- Create frontend tests beside pure planning helpers.
- Modify router, app navigation, task types/API/board, styles, package scripts, and lockfile.

**Interfaces:**
- The API client exposes every backend route and persists one confirmation UUID until an explicit result.
- The poller owns exactly one timer: 2 seconds for 30 seconds, then 5 seconds, paused when hidden and stopped on stable state/unmount/project change.

- [ ] Add Vitest and failing tests for polling, project isolation, dependency deletion/cycle detection, dirty snapshots, confirmation-key reuse, and source-plan highlighting.
- [ ] Confirm RED.
- [ ] Implement list/create/status/actions/editor/history/restore/dirty-conflict/confirm flow and task-board highlighting without unsafe `v-html`.
- [ ] Run `pnpm typecheck`, `pnpm test`, and `pnpm build`; expect exit 0.
- [ ] Run `git diff --check`; commit `feat(frontend): add AI task planning workflow`.

### Task 8: Synchronize contracts and learning documentation

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/architecture.md`
- Modify: `docs/database.md`
- Create: `docs/learning/phase-08-ai-task-planning.md`

**Interfaces:**
- OpenAPI describes the implemented immutable-version endpoints, permissions, errors, 202/409/422/429/503/504 behavior, and no active overwrite-style draft PUT.

- [ ] Compare every controller mapping and DTO to OpenAPI.
- [ ] Document two-stage generation, injection boundaries, versions, state machine, cancellation, validator/DAG, confirmation/rollback, Redis role, recovery, and manual acceptance.
- [ ] Run documentation placeholder and secret scans; expect no unresolved placeholders or secrets.
- [ ] Run `git diff --check`; commit `docs: document Phase 08 AI task planning`.

### Task 9: Full verification, review, and final commit audit

**Files:**
- Verify all Phase 08 changes and committed artifacts.

**Interfaces:**
- Produces reproducible command evidence and a clean feature-branch history without push/merge.

- [ ] Run `.\mvnw.cmd test` and `.\mvnw.cmd clean package`; expect exit 0 and zero failures.
- [ ] Run `corepack pnpm typecheck`, `corepack pnpm test`, and `corepack pnpm build`; expect exit 0.
- [ ] Validate V1–V5 on empty PostgreSQL and V5 upgrade from V4 when local Docker dependencies are available.
- [ ] Run `git diff --check`, `git diff --cached --check`, artifact scan, tracked-file secret scan, `git status --short`, and `git log --oneline --decorate -12`.
- [ ] Request an independent code review, fix all critical/important findings, then rerun impacted and full verification.
- [ ] Confirm every design acceptance item against implementation/tests and report any real external-only acceptance limitation accurately.
