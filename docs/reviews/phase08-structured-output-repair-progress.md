# Phase 08 Structured Output Contract Repair — Progress

## Git Baseline

- Branch: `feat/phase-08-ai-task-planning`
- HEAD: 63a417f → current
- .env: gitignored, not tracked ✓

## Bug Confirmation (S1–S5) → All FIXED

### S1: No JSON mode → FIXED
- Added `OutputFormat` enum to `ChatCompletionCommand`
- Added `ResponseFormat` record and `response_format` to `ChatRequest`
- Gateway sends `{"type":"json_object"}` when `JSON_OBJECT` is specified
- TEXT mode omits `response_format` via `@JsonInclude(NON_NULL)`

### S2: Skeleton has sources/sourceRefs → FIXED
- Removed `sources` from `SkeletonModelOutput`
- Removed `sourceRefs` from `SkeletonMilestone`
- Updated `SKELETON_SCHEMA` to remove `sources` and `sourceRefs`
- Updated `toDraft()` to use `List.of()` for sources
- `validSkeleton()` still enforces empty sourceRefs on draft

### S3: Detail only tempKey required → FIXED
- Updated `DETAIL_SCHEMA` with required fields: description, priority, dependencyTempKeys, sourceRefs
- Added `additionalProperties:false` to nested milestone/task objects
- Added `validateDetailRequiredFields()` in parser for post-deserialization enforcement

### S4: Error swallowing → FIXED
- Created `ModelOutputContractException` with category and jsonPath
- Parser now throws structured exceptions with safe field paths
- Added `safeErrorSummary()` to orchestrator for safe diagnostics
- `fail()` overload accepts custom errorSummary
- Error summary format: "STAGE / CATEGORY / field.path"

### S5: finish_reason not checked → FIXED
- Added `finish_reason` to `ChatChoice` record
- Added `checkFinishReason()` method
- `finish_reason=length` throws `AI_PROVIDER_OUTPUT_TRUNCATED`
- Mapped to `PLANNING_MODEL_OUTPUT_TRUNCATED` in ModelClient

## TDD Cycle — All GREEN

| # | Test | Status | File |
|---|------|--------|------|
| T1 | JSON mode request format | ✅ GREEN | OpenAiCompatibleChatModelGatewayTest |
| T2 | Text command omits response_format | ✅ GREEN | OpenAiCompatibleChatModelGatewayTest |
| T3 | Skeleton no sources/sourceRefs | ✅ GREEN | TaskPlanOutputParserTest |
| T4 | Detail only tempKey rejected | ✅ GREEN | TaskPlanOutputParserTest |
| T5 | Legal Skeleton+Detail → READY | ✅ GREEN | TaskPlanGenerationOrchestratorIntegrationTest |
| T6 | First invalid → Repair → success | ✅ GREEN | TaskPlanGenerationOrchestratorIntegrationTest |
| T7 | Two invalid → safe failure | ✅ GREEN | TaskPlanGenerationOrchestratorIntegrationTest |
| T8 | finish_reason=length → truncated | ✅ GREEN | OpenAiCompatibleChatModelGatewayTest |

## Orchestration Tests — All GREEN

| # | Test | Status | Assertions |
|---|------|--------|-----------|
| O1 | Legal two-phase → READY | ✅ | status=READY, activeAttemptId=null, sources from server, AI_COMPLETE content |
| O2 | First invalid → Repair → READY | ✅ | Repair prompt receives structured error,最终 READY |
| O3 | Two invalid → FAILED | ✅ | status=FAILED, lastErrorCode set, lastErrorSummary contains "SKELETON" |
| O4 | finish_reason=length → truncated | ✅ | status=FAILED, errorCode=PLANNING_MODEL_OUTPUT_TRUNCATED |

## Test Results

- Backend: 87 tests, 0 failures
- Frontend: 21 tests, 0 failures
- Frontend typecheck: PASS
- Frontend build: PASS

## Production Changes

| Fix | Files Modified |
|-----|---------------|
| S1: OutputFormat | ChatCompletionCommand, OpenAiCompatibleChatModelGateway, TaskPlanModelClient |
| S2: Skeleton contract | SkeletonModelOutput, TaskPlanGenerationOrchestrator (SKELETON_SCHEMA, toDraft) |
| S3: Detail contract | TaskPlanGenerationOrchestrator (DETAIL_SCHEMA), TaskPlanOutputParser |
| S4: Structured diagnosis | ModelOutputContractException (new), TaskPlanOutputParser, TaskPlanGenerationOrchestrator, TaskPlanRepository |
| S5: finish_reason | OpenAiCompatibleChatModelGateway (ChatChoice, checkFinishReason), ErrorCode, TaskPlanModelClient |
| Frontend | api-result.ts (PLANNING_MODEL_OUTPUT_TRUNCATED) |

## Dependencies Added

- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (compile scope) — for LocalDate serialization in production runtime

## Final Correction (2026-07-27)

### P0-1: TaskPlanModelClient.generate() now uses JSON_OBJECT
- Modified `generate()` to pass `ChatCompletionCommand.OutputFormat.JSON_OBJECT`
- Added `TaskPlanModelClientTest` verifying `outputFormat() == JSON_OBJECT`

### P0-2: jackson-datatype-jsr310 moved to compile scope
- Changed from `<scope>test</scope>` to compile scope in pom.xml
- Production runtime can now serialize/deserialize LocalDate in TaskPlanDraft

### P0-3: fail(errorSummary) now has @Transactional
- Added `@Transactional` annotation to the new `fail()` overload
- Ensures SELECT FOR UPDATE + UPDATE plan + UPDATE attempt in single transaction

### P1-1: Repair Prompt now receives structured failure info
- Modified `repairPrompt()` to accept stage, category, jsonPath, validationCodes
- `ModelOutputContractException` now carries `List<String> validationCodes`
- Repair prompt includes: stage, category, path, validation_codes

### P1-2: Nullable required fields enforced
- SKELETON_SCHEMA milestone required: tempKey, title, objective, targetDate, sortOrder
- DETAIL_SCHEMA task required: tempKey, description, priority, estimatedHours, startDate, dueDate, suggestedAssigneeId, dependencyTempKeys, sourceRefs
- Parser uses JsonNode pre-check to distinguish "omitted" (reject) from "explicit null" (accept)

### P1-3: Safe path extraction improved
- Uses `JsonMappingException.getPath()` for structured field paths
- Falls back to `UnrecognizedPropertyException.getPropertyName()`
- No raw exception message or JSON content in summaries

### P1-4: Frontend failure handling tests added
- Added `planning-failure.test.ts` with 10 tests
- Verifies safe summary preference, truncation handling, raw output exclusion, retry/regenerate permissions

## Test Results (After Final Correction)

- Backend: 93 tests, 0 failures
- Frontend: 31 tests, 0 failures
- Frontend typecheck: PASS
- Frontend build: PASS
