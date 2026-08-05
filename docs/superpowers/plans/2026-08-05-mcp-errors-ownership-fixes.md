# MCP Contract, Global Error Toasts, and Ownership Transfer Implementation Plan

> **For agentic workers:** Execute inline on the existing `main` workspace. The user explicitly forbids branches, worktrees, commits, pushes, and PRs.

**Goal:** Repair MCP connection response/edit flows, replace request-failure page alerts with one contextual top-level error toast, and make ownership transfer transactionally correct under PostgreSQL constraints and concurrency.

**Architecture:** Convert persisted MCP JSON into explicit immutable Java collection DTOs at the response boundary and validate the same contract in the TypeScript API boundary. Centralize UI error normalization, contextual wording, and short-window grouping in one helper consumed exactly once by each operation. Serialize ownership transfer on the project row, validate roles after locking, update the owner field and membership roles with checked row counts in a single transaction, and preserve the partial unique OWNER index by demoting before promoting.

**Tech Stack:** Java 21, Spring Boot, Jackson, MyBatis, PostgreSQL/Testcontainers, Vue 3, TypeScript, Element Plus, Vitest.

## Global Constraints

- Preserve every unrelated dirty-worktree change and edit overlapping files minimally.
- Write and run a failing regression test before each production behavior change.
- Do not use `any`, non-null assertions, empty catches, or interceptor-level duplicate notifications.
- Keep form validation, persistent business status, empty states, and informational alerts.
- Synchronize Java views/errors, OpenAPI, TypeScript contracts, Chinese mappings, and authoritative docs.

### Task 1: MCP response and editor contract

**Files:** MCP view/service/controller tests; `McpConnectionView.java`; admin API/types/state/view tests and code; OpenAPI.

- [ ] Add a controller/Jackson regression test proving all four JSON-backed fields serialize as ordinary arrays and run it red.
- [ ] Add frontend boundary tests for valid arrays, malformed payload rejection, allowlist edit refill, credential omission/rotation, and create/edit/discover/enable flow; run red.
- [ ] Replace response `JsonNode` fields with `List<String>` plus typed discovered tool/resource DTO lists; reject malformed persisted JSON with a stable contract error.
- [ ] Parse unknown HTTP payloads at the admin API boundary, return a specific contract error for malformed arrays, and keep blank edit credentials as no rotation.
- [ ] Run focused backend/frontend tests green and update OpenAPI.

### Task 2: Global contextual API error messages

**Files:** `src/api/api-result.ts` and tests; every `src/**/*.vue` request/operation catch and its affected tests.

- [ ] Add failing unit/component tests for contextual text, backend detail preservation, grouping, and one notification per failed operation.
- [ ] Implement `showApiError(error, action, fallback?)` using `normalizeApiError` and Element Plus grouping/deduplication.
- [ ] Replace request-failure page state/alerts across `src`, supplying operation-specific action text and calling the helper once per catch.
- [ ] Preserve non-request validation/status/info alerts and cancellation behavior.
- [ ] Run focused tests, full frontend tests, typecheck, and build.

### Task 3: Transactional ownership transfer

**Files:** project mapper/repositories/service/error code; PostgreSQL integration tests; project frontend API/view tests; OpenAPI and docs.

- [ ] Add PostgreSQL integration tests reproducing incomplete `MemberView` mapping and covering MEMBER, ADMIN, self, missing target, non-owner, concurrent transfer, permissions, rollback, and audit; run red.
- [ ] Lock the project row, validate the current owner and target membership under the lock, and return only a UUID/boolean from lock queries.
- [ ] Check every update count; update project owner, demote old owner, then promote new owner in one transaction; map stale/concurrent outcomes to stable business errors.
- [ ] Refresh project/member context after success and show one contextual failure toast after failure.
- [ ] Run focused backend/frontend tests green and synchronize ErrorCode, OpenAPI, feature matrix, and database facts.

### Task 4: Final verification

- [ ] Run backend full tests and clean package.
- [ ] Run frontend full tests, typecheck, and production build.
- [ ] Run OpenAPI validation, `git diff --check`, and `git status --short`.
- [ ] Report exact exit codes, test totals, build results, root causes, touched files, and only genuine external dependencies not exercised.
