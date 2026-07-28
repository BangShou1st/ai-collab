# Phase 08 Final Continuation Progress

## WIP Snapshot (2026-07-28)

**Branch:** `feat/phase-08-editable-degradation`
**HEAD:** `bbd15f4` — docs: correct Phase 08 editable degradation status

### Modified files (uncommitted):
- `TaskPlanCommandService.java` (+133/-10)
- `TaskPlanGenerationOrchestrator.java` (+17/-2)
- `TaskPlanVersionCommitService.java` (+7)
- `Phase08EditableDegradationIntegrationTest.java` (2 changes)
- `TaskPlanEditTest.java` (2 changes)
- `TaskPlanPartialRegenerateTest.java` (2 changes)
- `TaskPlanRepositoryIntegrationTest.java` (2 changes)

### New files (untracked):
- `V8__fix_status_check_constraint.sql`
- `V9__add_partial_repair_source_type.sql`
- `TaskPlanProductionWiringPostgresIT.java`

### Patch saved:
- `E:/phase08-final-continuation-wip.patch`
- `E:/phase08-final-continuation-status.txt`

---

## Phase A: Root Cause — appendVersion hardcodes READY status

**根因确认**：`TaskPlanRepository.appendVersion()` 第119行 SQL 硬编码 `WHEN ?='AI_COMPLETE' THEN 'READY'`，忽略了 `outcomeDecider` 计算出的 `finalStatus`（READY_WITH_ISSUES）。

**调用链断裂**：
```
outcomeDecider.decideStatus() → READY_WITH_ISSUES
  ↓ (finalStatus 参数)
commitService.commit(plan, draft, AI_COMPLETE, assessment, finalStatus=READY_WITH_ISSUES, ...)
  ↓ (finalStatus 未传递！)
repository.appendGeneratedVersion(..., assessment.toFlat())  ← 缺 finalStatus
  ↓
appendVersion(... type="AI_COMPLETE" ...)
  ↓ SQL 硬编码
WHEN ?='AI_COMPLETE' THEN 'READY'  ← 永远是 READY
```

**修复**：
1. `appendVersion()` 新增 `TaskPlanStatus finalStatus` 参数
2. SQL 改为 `ELSE ?` 使用传入的 finalStatus
3. `appendGeneratedVersion()` 透传 finalStatus
4. `commitService.commit()` 透传 finalStatus
5. `save()`/`restore()` 传入 `TaskPlanStatus.READY`
6. `runSkeleton()` 传入 `TaskPlanStatus.DETAIL_GENERATING`

**测试验证**：`repairStillHasDependencyDateConflictCommitsReadyWithIssues` GREEN

**Commit**: pending

---

## Phase B: V8/V9 Alignment Audit

*Status: PENDING*

---

## Phase B: Root Cause Trace

*Status: PENDING*

---

## Phase C: Production Code Fixes

*Status: PENDING*

---

## Phase D: Test Green

*Status: PENDING*

---

## Phase E: Commit & Closure

*Status: PENDING*
