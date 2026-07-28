# Phase 08 Production Wiring Audit

**Date:** 2026-07-28
**Branch:** feat/phase-08-editable-degradation
**HEAD:** 18d1c26

## Summary

This audit verifies whether each Phase 08 component is actually called by production code (not just defined or tested). Components with only definitions and test references are marked DEAD / NOT WIRED.

---

## DEAD / NOT WIRED Components

### 1. PlanningPromptPolicy

| Attribute | Value |
|-----------|-------|
| Definition | `PlanningPromptPolicy.java:29` (`@Component`) |
| Production callers | **NONE** — only referenced in its own definition file |
| Test references | `PlanningPromptPolicyTest.java`, `Phase08EditableDegradationIntegrationTest.java` |
| Injected into Orchestrator | **NO** — Orchestrator constructor does not accept `PlanningPromptPolicy` |
| Status | **DEAD / NOT WIRED** |

### 2. TaskPlanRepairPatchParser

| Attribute | Value |
|-----------|-------|
| Definition | `TaskPlanRepairPatchParser.java:22` (`@Component`) |
| Production callers | **NONE** — only its own definition |
| Test references | None found |
| Injected into any service | **NO** |
| Status | **DEAD / NOT WIRED** |

### 3. TaskPlanRepairPatchApplier

| Attribute | Value |
|-----------|-------|
| Definition | `TaskPlanRepairPatchApplier.java:25` (`@Component`) |
| Production callers | **NONE** — only its own definition |
| Test references | `TaskPlanRepairPatchTest.java` (uses `new TaskPlanRepairPatchApplier()`) |
| Injected into any service | **NO** |
| Status | **DEAD / NOT WIRED** |

### 4. TaskPlanVersionCommitService

| Attribute | Value |
|-----------|-------|
| Definition | `TaskPlanVersionCommitService.java:24` (`@Service`) |
| Production callers | **NONE** — not called by Orchestrator, CommandService, or any other production code |
| Test references | `TaskPlanVersionCommitServiceTest.java` (uses `new TaskPlanVersionCommitService(...)`) |
| Injected into Orchestrator | **NO** |
| Injected into CommandService | **NO** |
| Status | **DEAD / NOT WIRED** |

### 5. PlanningIssuePanel.vue

| Attribute | Value |
|-----------|-------|
| Definition | `components/PlanningIssuePanel.vue` |
| Frontend callers | **NONE** — not imported by PlanningView.vue or any other component |
| Status | **DEAD / NOT WIRED** |

### 6. PlanningEventTimeline.vue

| Attribute | Value |
|-----------|-------|
| Definition | `components/PlanningEventTimeline.vue` |
| Frontend callers | **NONE** — not imported by PlanningView.vue or any other component |
| Status | **DEAD / NOT WIRED** |

### 7. planning-edit.ts

| Attribute | Value |
|-----------|-------|
| Definition | `planning-edit.ts` |
| Frontend callers | **NONE** — not imported by PlanningView.vue |
| Status | **DEAD / NOT WIRED** |

---

## PARTIALLY WIRED (手工 new, NOT Spring injected)

### 8. TaskPlanDraftNormalizer

| Attribute | Value |
|-----------|-------|
| Definition | `TaskPlanDraftNormalizer.java:34` |
| Production usage | `TaskPlanCommandService.java:244` via `new TaskPlanDraftNormalizer()` |
| Spring injected | **NO** — created via `new` |
| Used by Orchestrator | **NO** |
| Status | **PARTIALLY WIRED — 手工 new, should be Spring-injected** |

### 9. GenerationOutcomeDecider

| Attribute | Value |
|-----------|-------|
| Definition | `GenerationOutcomeDecider.java:18` (`@Component`) |
| Production usage | `TaskPlanCommandService.java:254` via `new GenerationOutcomeDecider()` |
| Spring injected | **NO** — created via `new` |
| Used by Orchestrator | **NO** |
| Status | **PARTIALLY WIRED — 手工 new, should be Spring-injected** |

---

## FAKE IMPLEMENTATIONS

### 10. partialRegenerate

| Attribute | Value |
|-----------|-------|
| Location | `TaskPlanCommandService.java:328` |
| Code | `// For now, delegate to full regeneration via orchestrator` |
| Behavior | Calls `orchestrator.dispatch(regenerated, actor, false)` — full generation, NOT scoped repair |
| Status | **FAKE — violates approved design** |

### 11. Title patch (no-op)

| Attribute | Value |
|-----------|-------|
| Location | `TaskPlanCommandService.java:273` |
| Code | `// Title patch applies to plan-level (not directly, but we keep it for future)` |
| Behavior | Accepts request but does nothing |
| Status | **NO-OP — should either implement or reject** |

### 12. buildAssessment (temporary bridge)

| Attribute | Value |
|-----------|-------|
| Location | `TaskPlanCommandService.java:336` |
| Code | `/** Build ValidationAssessment from flat ValidationResult (temporary bridge). */` |
| Behavior | Forces ALL errors to `BLOCKING_EDITABLE` severity, ignoring actual severity from ValidationIssueCatalog |
| Status | **TEMPORARY BRIDGE — bypasses structured validation** |

---

## PRODUCTION CALL CHAIN GAPS

### Orchestrator (TaskPlanGenerationOrchestrator)

| Component | Used? | Gap |
|-----------|-------|-----|
| PlanningPromptPolicy | **NO** | Prompts are built inline in `skeletonPrompt()` and `detailPrompt()` |
| TaskPlanDraftNormalizer | **NO** | No normalization before validation |
| Structured validation issues | **NO** | Only flat `valid()` boolean used, no target/field info passed to repair |
| TaskPlanRepairPatchParser | **NO** | Not used anywhere |
| TaskPlanRepairPatchApplier | **NO** | Not used anywhere |
| GenerationOutcomeDecider | **NO** | Status decision is hardcoded in catch blocks |
| TaskPlanVersionCommitService | **NO** | Uses `repository.appendGeneratedVersion()` directly |

### CommandService (TaskPlanCommandService)

| Component | Used? | Gap |
|-----------|-------|-----|
| TaskPlanDraftNormalizer | **YES** (via `new`) | Should be Spring-injected |
| GenerationOutcomeDecider | **YES** (via `new`) | Should be Spring-injected |
| TaskPlanVersionCommitService | **NO** | Edit uses `repository.appendVersion()` + `jdbc.update()` separately |
| Structured issues | **NO** | `buildAssessment()` is a temporary bridge |
| Events | **NO** | No event written for USER_EDIT |

### OpenAPI

| Item | Present? |
|------|----------|
| READY_WITH_ISSUES status | **NO** |
| REPAIRING status | **NO** |
| PATCH /edit endpoint | **NO** |
| POST /partial-regenerate endpoint | **NO** |
| GET /events endpoint | **NO** |
| structured validationIssues in response | **NO** |
| canPartialRegenerate permission | **NO** |
| TASK_PLAN_HAS_BLOCKING_ISSUES error | **NO** |
| VERSION_CONFLICT error | **NO** |

---

## REQUIRED FIXES (Priority Order)

1. **Orchestrator**: Inject `PlanningPromptPolicy`, use it for prompt generation
2. **Orchestrator**: Pass structured validation issues to repair prompt (not just flat codes)
3. **Orchestrator**: Use `GenerationOutcomeDecider` for status decision
4. **CommandService edit**: Inject `TaskPlanVersionCommitService`, `TaskPlanDraftNormalizer`, `GenerationOutcomeDecider`
5. **CommandService edit**: Use atomic `VersionCommitService.commit()` instead of separate `appendVersion` + `jdbc.update`
6. **CommandService edit**: Write issues and events atomically
7. **CommandService edit**: Reject unknown targets (currently `continue` silently)
8. **CommandService edit**: Remove `buildAssessment()` temporary bridge
9. **CommandService partialRegenerate**: Implement real scoped repair instead of full generation delegation
10. **Frontend**: Mount `PlanningIssuePanel` and `PlanningEventTimeline` in `PlanningView.vue`
11. **Frontend**: Implement editing mode with PATCH and partial-regenerate UI
12. **OpenAPI**: Add all missing endpoints and schemas
13. **Tests**: Write RED integration tests with Spring Testcontainers
