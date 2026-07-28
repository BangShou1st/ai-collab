# Phase 08 AI Task Planning Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute each task, `superpowers:test-driven-development` for every behavior change, and `superpowers:verification-before-completion` before each completion claim.

**Goal:** Restore immutable Flyway compatibility and close every verified P0, P1, and P2 finding in the Phase 08 comprehensive review, with production-like migration, concurrency, frontend, documentation, startup, and regression evidence.

**Authoritative inputs, in precedence order:**

1. `docs/superpowers/specs/2026-07-26-phase-08-ai-task-planning-design.md`
2. `C:/Users/32962/Downloads/phase08-one-shot-repair-prompt.md`
3. `C:/Users/32962/Downloads/phase08-comprehensive-code-review.md`
4. Current implementation and this executable repair plan

**Constraints:** Stay on `feat/phase-08-ai-task-planning`; do not edit V1–V4, run `flyway repair`, delete a database or Docker volume, modify `.env`, push, merge, or switch to `main`. Restore V5 byte-for-byte from its first committed blob. Put every later schema change in V6. Model calls never run in a database transaction.

**Verified migration diagnosis:** Flyway 12.4.0 computes `-1995940233` for the V5 blob at `d7897ab` and `1512947011` for the V5 blob at `fb944f`/HEAD. The existing database applied the former. The `sqlSessionTemplate` failure is cascading startup damage after Flyway validation rejects the changed migration.

---

## Task 1: Freeze evidence, restore immutable V5, and design V6

**Files:**

- Restore: `ai-collab-backend/src/main/resources/db/migration/V5__create_ai_task_planning.sql`
- Create: `ai-collab-backend/src/main/resources/db/migration/V6__repair_phase_08_ai_task_planning.sql`
- Modify/Create migration integration tests under `ai-collab-backend/src/test/java/.../planning/infrastructure/`
- Create: `docs/reviews/phase08-comprehensive-code-review.md`

**TDD and verification:**

- [ ] Preserve the supplied review in the repository and record the two V5 checksums.
- [ ] Add failing PostgreSQL migration tests for empty V1→V6, empty V4→V6, V4 with planning data blocked before destructive V5 work and retaining all rows, and original V5→V6.
- [ ] Add a byte/checksum regression test proving V1–V5 are immutable and V5 remains `-1995940233`.
- [ ] Restore V5 exactly from `d7897ab`.
- [ ] Add a pre-V5 guard that permits fresh/empty placeholder tables but aborts when any Phase 04 planning rows exist.
- [ ] Add V6 for all schema repairs, including confirmation deletion semantics and repair-only columns/indexes/constraints needed by later tasks.
- [ ] Run targeted migration tests against temporary PostgreSQL, then full backend tests.
- [ ] Commit `fix(db): restore immutable V5 and add Phase 08 repair migration`.

## Task 2: Split AI stage contracts and make validation strict

**Files:**

- Add stage-specific model DTOs under `.../planning/domain/` or `.../planning/application/`
- Modify `TaskPlanOutputParser`, `TaskPlanDraftValidator`, `TaskPlanGenerationOrchestrator`
- Extend domain/parser/orchestrator tests

**TDD and verification:**

- [ ] Add failing tests proving AI skeleton/detail output cannot contain formal `assigneeId`, unknown JSON properties are rejected, required arrays are present, and empty plans are rejected.
- [ ] Add failing tests for duplicate dependencies, duplicate source refs, malformed ref formats, max sizes, contiguous sort order, and real skeleton invariance across detail generation.
- [ ] Add failing tests proving AI completion always persists `assigneeId = null` while retaining `recommendedAssigneeId`.
- [ ] Introduce separate strict skeleton/detail model DTOs; retain manual draft DTOs with formal assignee support.
- [ ] Make `TaskPlanDraftValidator` the single complete domain validation entry and return structured errors/warnings.
- [ ] Run targeted and full backend tests.
- [ ] Commit `fix(planning): enforce strict staged model contracts`.

## Task 3: Secure prompts and preserve all generated fields

**Files:**

- Modify `PlanningPromptText`, `TaskPlanContextAssembler`, `TaskPlanModelClient`, and orchestrator merge logic
- Extend prompt/context/orchestrator tests

**TDD and verification:**

- [ ] Add failing hostile-input tests for `&`, `<`, `>`, boundary-looking tags, control text, and source-content quotation attacks.
- [ ] Add failing budget tests proving the complete prompt—not only document excerpts—stays inside the configured Unicode code-point budget.
- [ ] Add failing merge tests for summary, assumptions, risks, dates, estimates, priorities, sources, dependencies, and sort values.
- [ ] Encode all untrusted prompt fields with generic XML escaping or structured safe encoding; never place raw model-controlled/source-controlled closing tags in the prompt.
- [ ] Keep stage-2 context minimal but sufficient: immutable skeleton identifiers plus only necessary project/member/work context, without duplicating source quotations.
- [ ] Preserve every skeleton field unless the detail contract explicitly owns it.
- [ ] Run targeted and full backend tests.
- [ ] Commit `fix(planning): harden prompts and preserve generated plans`.

## Task 4: Make async generation race-safe and operationally accurate

**Files:**

- Modify `PlanningAsyncConfiguration`, `PlanningGenerationRateLimiter`, `TaskPlanCommandService`, `TaskPlanGenerationOrchestrator`, repository
- Add deterministic executor/future/rate-limit tests

**TDD and verification:**

- [ ] Add failing no-sleep tests for cancel-before-start, cancel-during-call, late completion, regenerate then old completion, queue rejection at each stage, and registry cleanup.
- [ ] Add failing repository/service tests for CAS `markRunning`, stale generation sequence, and retry preconditions.
- [ ] Add failing Redis tests for atomic INCR+TTL, process-shared limit behavior, Redis outage fallback, and the rule that only valid/high-cost starts consume quota.
- [ ] Introduce a per-attempt cancellable `Future` registry with cleanup; cancel running work when requested.
- [ ] Recheck active state before each provider call and discard any late result after each call.
- [ ] Use CAS transitions and clear the active attempt when generation reaches a terminal state.
- [ ] Translate queue rejection to stable `503 PLANNING_QUEUE_FULL` and attribute the failing stage correctly.
- [ ] Resolve each nonblank model property independently in planning→chat→default order.
- [ ] Persist actual attempt metrics and operator identity.
- [ ] Run targeted and full backend tests.
- [ ] Commit `fix(planning): close generation races and rate limits`.

## Task 5: Repair idempotent confirmation and transaction boundaries

**Files:**

- Modify `TaskPlanConfirmationService`, repository, confirmation DTO/result mapping
- Add PostgreSQL concurrency and injected-failure tests

**TDD and verification:**

- [ ] Add failing test: a confirmation key fails, plan is regenerated to a new READY version, then the old key must return stable `409`, not replay a failed request.
- [ ] Add failing tests for a new key after FAILED confirmation, JSONB replay as `List<UUID>`, same-key races, two-admin races, member revalidation, and confirmed-project deletion conflict.
- [ ] Add four rollback injection tests at milestone, task, dependency, and audit boundaries.
- [ ] Recheck plan state, latest version, generation sequence, active generation, and request hash before any FAILED retry.
- [ ] Keep confirmation claim/finalization semantics stable while ensuring the formal work landing and success status are atomic.
- [ ] Return stable errors without leaking SQL, prompt, raw model output, or stack traces.
- [ ] Run targeted and full backend tests.
- [ ] Commit `fix(planning): repair confirmation idempotency and rollback`.

## Task 6: Complete API semantics, query shape, and manual lifecycle

**Files:**

- Modify planning controller/services/repository/API DTOs and common exception mappings
- Add controller/service/repository tests

**TDD and verification:**

- [ ] Add failing tests for rich detail response plus lightweight version history, persisted validation results, actor metadata, and accurate active-attempt state.
- [ ] Add failing tests for manual save transactionality, restore, conflicts, deletion, invalid UUID/header/query parameters, and stable 400/403/404/409/422/429/503/504 mappings.
- [ ] Persist and return `validation_result` errors and warnings on generated/manual versions.
- [ ] Make manual version creation atomic and base-version guarded.
- [ ] Ensure all audit/version records use the actual operator.
- [ ] Enforce confirmed-plan project deletion as a stable business conflict.
- [ ] Run targeted and full backend tests/package.
- [ ] Commit `fix(planning): complete lifecycle API semantics`.

## Task 7: Complete and verify the frontend workflow

**Files:**

- Modify `ai-collab-frontend/src/modules/planning/` and task-board provenance UI
- Add/extend Vitest component and helper tests

**TDD and verification:**

- [ ] Inventory the existing real frontend before changing it; map every design acceptance behavior to a component/test.
- [ ] Add failing tests for permissions, project isolation, poll timing/visibility/cleanup, dirty navigation, dependency cleanup/cycle detection, validation result placement, version conflict, persistent confirmation key reuse, retry conflict, XSS rendering, and source-plan highlighting.
- [ ] Implement missing list/create/detail/status/editor/history/restore/cancel/retry/regenerate/delete/confirm behavior with no unsafe `v-html`.
- [ ] Display persisted errors/warnings in the right-hand validation panel and confirmation counts in the modal.
- [ ] Run `corepack pnpm typecheck`, `corepack pnpm test`, and `corepack pnpm build`.
- [ ] Commit `fix(frontend): complete Phase 08 planning workflow`.

## Task 8: Synchronize OpenAPI and operational documentation

**Files:**

- Modify `docs/api/openapi.yaml`, `docs/architecture.md`, `docs/database.md`
- Modify `docs/learning/phase-08-ai-task-planning.md`
- Create `docs/reviews/phase08-repair-verification.md`

**Verification:**

- [ ] Compare every planning controller path, request, response, header, status, permission, and error to OpenAPI.
- [ ] Document immutable V5 recovery, V6 upgrade paths, pre-V5 data guard, staged strict DTOs, prompt safety, validation results, cancellation, rate limiting, idempotency, rollback, and frontend behavior.
- [ ] Document reproducible commands and actual pass/fail evidence; do not claim unavailable external acceptance.
- [ ] Run placeholder, secret, generated-artifact, and contract scans.
- [ ] Commit `docs: document Phase 08 repair verification`.

## Task 9: Existing-database startup, independent review, and final audit

**Verification:**

- [ ] Run the exact Flyway 12.4.0 checksum check and all four PostgreSQL migration routes.
- [ ] Validate/migrate the existing user database without deleting or repairing it.
- [ ] Run `.\mvnw.cmd test` and `.\mvnw.cmd clean package`.
- [ ] Run all frontend checks.
- [ ] Start Spring Boot locally, confirm Tomcat port 8080 and a health/authenticated endpoint, then stop only the process started for this verification.
- [ ] Run `git diff --check`, cached diff check, V1–V5 immutability comparison, secret/artifact scans, status, and commit audit.
- [ ] Request an independent code review mapped to every P0/P1/P2 item; fix all verified Critical/Important findings and rerun impacted/full checks.
- [ ] Complete `docs/reviews/phase08-repair-verification.md` with an acceptance matrix and precise evidence.
- [ ] Commit any final review corrections as a focused commit.
