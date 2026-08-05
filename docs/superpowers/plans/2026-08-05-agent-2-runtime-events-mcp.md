# Agent 2.0 Runtime, Events, MCP, and Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. The repository policy requires all work on `main`; do not create a branch or worktree. Do not commit unless the user explicitly requests it.

**Goal:** Complete the accepted `docs/agent` specification from the current Phase 2 recovery point through API contracts, event/SSE workspace, controlled MCP, project memory, and final acceptance.

**Architecture:** Preserve the existing database-claimed single-Agent runtime. Add durable project-scoped events as the UI source of truth, expose replayable authenticated SSE, then add MCP behind a project-scoped provider facade and server-owned allowlists. All internal writes and memory writes remain approval-gated and are revalidated and read back after execution.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, JdbcTemplate, PostgreSQL/Flyway, Jackson, Vue 3, TypeScript 5.8, Pinia-compatible composition state, Vitest, pnpm.

## Global Constraints

- Work only on `main`; do not create a branch or worktree.
- Preserve unrelated dirty-worktree changes and never rewrite V1-V27 migrations.
- Every repository query for an Agent subresource includes `project_id`.
- Models cannot override `projectId`, permissions, risk, timeouts, or budgets.
- External model/MCP calls stay outside database transactions.
- No prompt, credential, nonce, full tool result, document body, stack trace, SQL, or absolute internal path enters logs/events.
- Production behavior changes follow red-green-refactor; PostgreSQL isolation and concurrency checks use Testcontainers.

---

### Task 1: Close Phase 2 approval and API contract gaps

**Files:**
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentApprovalService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentApprovalRepository.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/feature-matrix.md`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentRepositoryIntegrationTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/AgentApprovalServiceTest.java`

**Interfaces:** Approval resolution is idempotent by `(approvalId, idempotencyKey)`, revalidates the current project resource/version/permissions, executes through the formal Application Service, persists a sanitized verified result, and requeues exactly once.

- [ ] Add failing tests for same-key replay, different-key conflict, permission removal, version conflict, and post-write readback.
- [ ] Run the focused tests and confirm failures identify missing idempotency/readback behavior.
- [ ] Implement the minimal transaction-safe repository/service behavior and safe error mapping.
- [ ] Update OpenAPI submit/run/approval schemas for `skillCode`, controlled `pageContext`, plan fields, and Phase 2 errors.
- [ ] Run focused tests, OpenAPI validation, and `git diff --check`.

### Task 2: Durable Agent events and cancellation checkpoints

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V28__add_agent_run_events.sql`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/domain/model/AgentEventType.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/view/AgentRunEventView.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentEventRepository.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentEventService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/repository/AgentRepository.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/AgentRunService.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/infrastructure/AgentEventRepositoryIntegrationTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeEventTest.java`

**Interfaces:** `append(projectId, runId, type, safePayload)` atomically increments `agent_run.last_event_sequence`; `list(projectId, runId, afterSequence, limit)` returns strictly increasing project-scoped events. Cancel first records `cancel_requested_at`; coordinator checkpoints finalize `CANCELED` before any later side effect.

- [ ] Add failing PostgreSQL tests for concurrent unique sequence, replay cursor, project isolation, and cancel-request checkpoints.
- [ ] Add V28 with `last_event_sequence`, `cancel_requested_at`, and `agent_run_event` constraints/indexes.
- [ ] Implement atomic sequence allocation, safe event persistence, and runtime event emission.
- [ ] Implement requested-then-final cancellation semantics at model/tool/requeue boundaries.
- [ ] Run focused event/runtime tests and full backend tests.

### Task 3: Authenticated replayable SSE API

**Files:**
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/controller/AgentEventController.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/api/dto/AgentRunEventResponse.java`
- Create: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentEventStreamService.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java`
- Modify: `docs/api/openapi.yaml`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/api/AgentEventControllerIntegrationTest.java`
- Test: `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/application/runtime/AgentEventStreamServiceTest.java`

**Interfaces:** `GET /api/v1/projects/{projectId}/agent/runs/{runId}/events` accepts non-negative `afterSequence` and optional numeric `Last-Event-ID`, replays DB events, registers an in-memory emitter, performs a second replay, sends heartbeats, and removes completed/failed/terminal subscriptions.

- [ ] Add failing API/service tests for cursor validation, replay, registration gap, cross-project access, heartbeat exclusion, and cleanup.
- [ ] Implement controller and stream service with after-commit publication.
- [ ] Document `text/event-stream`, event schemas, errors, and replay semantics in OpenAPI.
- [ ] Run focused HTTP/SSE tests and OpenAPI validation.

### Task 4: Vue Agent workspace event timeline

**Files:**
- Create: `ai-collab-frontend/src/modules/agent/agent-event-stream.ts`
- Create: `ai-collab-frontend/src/modules/agent/agent-event-stream.test.ts`
- Create: `ai-collab-frontend/src/modules/agent/agent-run-store.ts`
- Create: `ai-collab-frontend/src/modules/agent/agent-run-store.test.ts`
- Create: `ai-collab-frontend/src/modules/agent/components/AgentContextChips.vue`
- Create: `ai-collab-frontend/src/modules/agent/components/AgentRunTimeline.vue`
- Create: `ai-collab-frontend/src/modules/agent/components/AgentApprovalCard.vue`
- Modify: `ai-collab-frontend/src/modules/agent/types.ts`
- Modify: `ai-collab-frontend/src/modules/agent/agent-api.ts`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.vue`
- Modify: `ai-collab-frontend/src/modules/agent/AgentView.test.ts`

**Interfaces:** submit accepts `{content, skillCode, pageContext}`; stream parsing supports CRLF, comments, multiline data, abort, last-sequence reconnect, and idempotent event application. Project/session switches abort the stream and clear incompatible run state.

- [ ] Add failing parser/store/component tests for replay idempotency, reconnect cursor, context removal, cancel display, approval pause, project switch abort, and narrow layout.
- [ ] Implement typed page context, event parser, state reducer, timeline/context/approval components, and fetch-based authorized streaming.
- [ ] Replace polling as the primary run timeline while retaining bounded HTTP refresh fallback.
- [ ] Run frontend tests, typecheck, and build.

### Task 5: Controlled MCP connections and project bindings

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V29__add_agent_mcp.sql`
- Create focused classes under `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/{api,application,domain,infrastructure}/mcp/`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/infrastructure/tool/AgentToolRegistry.java`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/common/exception/ErrorCode.java`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database.md`
- Test focused MCP policy/repository/controller/provider classes under `ai-collab-backend/src/test/java/com/shitulelv/aicollab/agent/`.

**Interfaces:** Business code depends on `McpClientFacade`; connection credentials never appear in views. Exposed tools equal system allowlist intersect project OWNER binding intersect confirmed discovery schema. HTTP endpoints pass HTTPS/host/DNS/redirect SSRF policy; external tools are server-classified read-only, timeout-bounded, sanitized, and project-scoped.

- [ ] Add failing tests for admin/OWNER authorization, project isolation, endpoint rejection, schema-change suspension, timeout mapping, injection containment, and credential redaction.
- [ ] Add V29 connection/binding tables with limits, versions, and allowlists.
- [ ] Implement facade, connection manager, endpoint policy, discovery/hash confirmation, repository/services/controllers, provider, and sanitizer without adding an unverified SDK dependency.
- [ ] Register only confirmed bound tools for the current context/Skill.
- [ ] Run MCP-focused tests, full backend tests, package, and OpenAPI validation.

### Task 6: Approval-gated project memory and schedule skill

**Files:**
- Create: `ai-collab-backend/src/main/resources/db/migration/V30__add_agent_memory_and_schedule_skill.sql`
- Create focused memory classes under `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/{api,application,domain,infrastructure}/memory/`
- Modify: `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentContextAssembler.java`
- Modify: schedule DTO/service/repository/view files under `ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/`
- Modify: frontend Agent types/API/workspace for memory and schedule skill selection.
- Modify: `docs/api/openapi.yaml`, `docs/database.md`, and `docs/feature-matrix.md`
- Test project memory isolation, approval behavior, load limit, creator permission loss, and scheduled write approval.

**Interfaces:** Only ACTIVE memories from the current project are loaded, at most 10, and selected deterministically. Create/update/disable is approval-required. Schedules store a validated built-in `skillCode`, run using the creator's current membership, auto-disable after membership loss, and never auto-approve.

- [ ] Add failing backend/frontend tests for memory isolation/limits/approval and scheduled permission/write behavior.
- [ ] Add V30 and implement project-scoped memory repositories/services/tools/APIs.
- [ ] Add validated schedule skill propagation and auto-disable behavior.
- [ ] Add memory/schedule workspace controls using escaped text only.
- [ ] Run focused and full verification.

### Task 7: Final contract and acceptance evidence

**Files:**
- Modify: `docs/agent/00_CURRENT_STATUS.md`
- Modify: `docs/feature-matrix.md`
- Modify: `docs/api/openapi.yaml`
- Modify: `docs/database.md`
- Create or modify Agent scenario tests to cover at least 30 fixed safety/runtime cases.

**Interfaces:** Documentation describes only verified behavior; unavailable real Provider/MCP credentials are explicitly reported as unexecuted rather than passed.

- [ ] Run all 30+ deterministic scenarios and security checks.
- [ ] Run `mvnw test`, backend package, frontend tests/typecheck/build, OpenAPI validation, `git diff --check`, and inspect `git status --short`.
- [ ] Start the application and capture health plus available HTTP/SSE smoke evidence.
- [ ] Update status/docs with exact command exits, runtime evidence, limitations, and no unsupported completion claims.
