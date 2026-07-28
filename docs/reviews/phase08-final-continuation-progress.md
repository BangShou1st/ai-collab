# Phase 08 Final Continuation Progress

## Status: Backend GREEN — 205/205 tests pass, BUILD SUCCESS

**Branch:** `feat/phase-08-editable-degradation`
**Date:** 2026-07-28

---

## Root Cause Fixes Applied

### Fix 1: TASK_PRIORITY_INVALID reclassified as HARD
- **File**: `ValidationIssueCatalog.java`
- **Change**: `TASK_PRIORITY_INVALID` moved from `BLOCKING_EDITABLE` to `HARD`
- **Why**: Invalid priority is a model contract violation (enum value not in `[LOW,MEDIUM,HIGH,URGENT]`), not a user-editable issue
- **Effect**: `hardRepairFailureEndsFailedWithoutPartialVersion` now correctly ends in `DETAIL_GENERATION_FAILED`

### Fix 2: active_attempt_id cleanup for all terminal states
- **File**: `TaskPlanRepository.appendVersion()` line 122
- **Before**: `active_attempt_id=CASE WHEN ?='AI_COMPLETE' THEN NULL ELSE active_attempt_id END`
- **After**: `active_attempt_id=CASE WHEN ?='AI_SKELETON' THEN active_attempt_id ELSE NULL END`
- **Why**: Manual edits, restores, and partial repairs should not leave dangling active attempts

### Fix 3: Interactive commit path (commitVersion)
- **File**: `TaskPlanVersionCommitService.java`
- **New method**: `commitVersion()` — atomic commit for edit/restore/partialRegenerate
- **Why**: `edit()` and `partialRegenerate()` don't have active generation attempts; the old `commit()` → `appendGeneratedVersion()` path caused NPE on null attemptId

### Fix 4: appendVersion state guard expanded
- **File**: `TaskPlanRepository.appendVersion()` line 100
- **Before**: `if (plan.status() != TaskPlanStatus.READY && !type.startsWith("AI_")) stateConflict()`
- **After**: Allows `READY` and `READY_WITH_ISSUES` for non-AI types
- **Why**: `edit()` needs to commit from `READY_WITH_ISSUES` status

### Fix 5: AI_PARTIAL source type for degraded generation
- **File**: `TaskPlanGenerationOrchestrator.runDetail()` line 283
- **Change**: Uses `AI_PARTIAL` source when `finalStatus == READY_WITH_ISSUES`
- **New enum value**: `TaskPlanVersionSource.AI_PARTIAL`
- **New migration**: `V10__add_ai_partial_source_type.sql`

### Fix 6: Removed new ObjectMapper() hot spot
- **File**: `TaskPlanCommandService.buildPatchPrompt()`
- **Change**: Uses injected `ObjectMapper` field instead of `new ObjectMapper().findAndRegisterModules()`

### Fix 7: Test fixture corrections
- `partialRepairKeepsLockedFieldsExactly`: Removed out-of-scope milestone patch
- `editReadyWithIssuesResolvesIssueAtomically`: Uses `commitVersion()` instead of `commit()`
- `Phase08MigrationSafetyIntegrationTest`: Updated to expect V10 migration

---

## Semantic Consistency Matrix

| Scenario | finalStatus | sourceType | active_attempt_id |
|---|---|---|---|
| Legal generation | READY | AI_COMPLETE | NULL |
| Conflict → repair → still editable | READY_WITH_ISSUES | AI_PARTIAL | NULL |
| HARD repair failure | DETAIL_GENERATION_FAILED | (no version) | NULL |
| Manual edit | READY or READY_WITH_ISSUES | MANUAL_EDIT | NULL |
| Partial AI repair | READY or READY_WITH_ISSUES | AI_PARTIAL_REPAIR | NULL |
| Restore | READY | RESTORED | NULL |

---

## Remaining Items

### Backend structural
- `toAssessment()` in orchestrator and command service creates `StructuredValidationIssue` with null `targetTempKey`/`field`/`relatedTempKey` — requires validator refactor to produce structured issues directly

### Frontend (pending backend completion)
- `@locate` and `@edit` handlers in PlanningView.vue still TODO
- AI repair should call partial-regenerate endpoint

---

## Test Results
- PostgresIT: 11/11 GREEN
- Full suite: 205/205 GREEN
- Build: SUCCESS
