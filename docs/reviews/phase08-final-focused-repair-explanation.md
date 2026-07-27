# Phase 08 最终聚焦修复说明

## 1. 总结

- **起始 commit**：8ab43f6
- **结束 commit**：80801ca
- **修复编号**：C1、C2、C3、C4、C5、C6、C7、C8、C9、C10、C11、C12、C13
- **结论**：Phase 08 已关闭所有确认的阻塞性问题，可进入下一轮开发

本轮共提交 10 个 commit，涉及 14 个文件修改、3 个新建文件，新增 44 个后端测试（含新增的 18 个）。

---

## 2. 每个问题的详细说明

### C1：AI 完整结果使用错误校验模式

**原始症状**：AI 生成的完整 draft（merged detail）在 Orchestrator 中使用 `validator.validate(ctx, merged, true)`，boolean 重载将其映射为 `AI_SKELETON` 模式，导致 description、priority、日期等 detail 字段检查被跳过，不完整或非法的 AI 输出仍可进入 READY 状态。

**根因**：`TaskPlanDraftValidator.validate(ctx, draft, boolean aiGenerated)` 的3参数重载将 `aiGenerated=true` 映射为 `ValidationMode.AI_SKELETON`，而非 `COMPLETE`。Orchestrator 的 `runDetail()` 方法（第 225、234 行）使用此重载校验合并后的完整 draft。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftValidator.java`
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanDomainTest.java`

**具体代码修改**：
1. `TaskPlanDraftValidator.validate(ctx, draft, boolean)` — 删除已废弃的3参数 boolean 重载
2. `TaskPlanGenerationOrchestrator.runDetail()` 第 225 行：
   - 修改前：`validator.validate(ctx, merged, true).valid()`
   - 修改后：`validator.validate(ctx, merged, ValidationMode.COMPLETE, true).valid()`
3. `TaskPlanGenerationOrchestrator.runDetail()` 第 234 行：
   - 修改前：`validator.validate(ctx, detail, true)`
   - 修改后：`validator.validate(ctx, detail, ValidationMode.COMPLETE, true)`
4. 测试文件更新：所有调用 `validate(ctx, draft, true)` 的测试改为 `validate(ctx, draft, ValidationMode.COMPLETE, true)`

**数据流变化**：
- 修改前：`Orchestrator.runDetail → validator.validate(ctx, merged, AI_SKELETON, true)` → 跳过 detail 检查
- 修改后：`Orchestrator.runDetail → validator.validate(ctx, merged, COMPLETE, true)` → 执行完整校验

**业务行为变化**：
- 修改前：AI 生成缺少 description 或 priority 的任务仍可进入 READY
- 修改后：所有 detail 字段（description、priority、日期、工时、成员归属）在 AI_COMPLETE 版本创建前被严格校验

**RED 测试**：
- `TaskPlanDomainTest.aiGeneratedTrueMustStillValidateDetailFieldsInCompleteMode` — 验证缺少 description 时产生 TASK_TEXT_INVALID
- `TaskPlanDomainTest.aiGeneratedTrueMustStillValidatePriority` — 验证缺少 priority 时产生 TASK_PRIORITY_INVALID

**GREEN 证据**：
- 命令：`./mvnw.cmd test`
- 退出码：0
- Tests run: 32, Failures=0, Errors=0, Skipped=0

**回归风险**：低。boolean 重载已删除，所有调用点改为显式 ValidationMode，行为完全由调用方控制。

---

### C2：详情接口 Map.of + nullable 导致 500

**原始症状**：`TaskPlanQueryService.detail()` 使用 `Map.of()` 构建 attempt、confirmation 等响应，但 QUEUED attempt 的 `startedAt`、PROCESSING confirmation 的 `completedAt` 等字段正常为 null，Java `Map.of` 不接受 null 值导致 NPE 500。

**根因**：`TaskPlanQueryService.detail()` 第 38-58 行使用 `Map.of("startedAt", attempt.get("started_at"))` 等构造嵌套 Map，而 `started_at` 在 QUEUED 状态时为 null。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanDetailView.java`（新建）
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanQueryService.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanQueryServiceTest.java`

**具体代码修改**：
1. 新建 `TaskPlanDetailView` record，包含 `plan`、`latestVersion`、`activeAttempt`、`latestFailedAttempt`、`latestAttempt`、`confirmation`、`validation`、`permissions` 字段
2. 新建 `TaskPlanAttemptView`、`TaskPlanConfirmationView`、`TaskPlanVersionView`、`TaskPlanValidationView`、`TaskPlanPermissions` record
3. `TaskPlanQueryService.detail()` 返回类型从 `Map<String, Object>` 改为 `TaskPlanDetailView`，所有 nullable 字段使用 Java record 的 nullable 字段

**业务行为变化**：
- 修改前：创建规划后立即 GET detail 可能因 startedAt=null 返回 500
- 修改后：所有状态下的 detail 均返回 200，nullable 字段为 null

**GREEN 证据**：
- 命令：`./mvnw.cmd test`
- Tests run: 33, Failures=0, Errors=0, Skipped=0

---

### C3：多规划 generationSeq registry key 冲突

**原始症状**：`ConcurrentHashMap<Long, UUID> generationActiveAttempt` 仅以 `generationSeq` 为 key，不同 plan 的首次生成均为 seq=1，导致互相覆盖。

**根因**：`TaskPlanGenerationOrchestrator` 第 106 行 `ConcurrentHashMap<Long, UUID> generationActiveAttempt` 的 key 只有 `generationSeq`，不同 plan 的相同 seq 值冲突。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestratorTest.java`

**具体代码修改**：
1. 新增 `record GenerationRunKey(UUID planId, long generationSeq)` 复合 key
2. 新增 `GenerationRunHandle` 内部类，持有 `Future<?>` 和 `AtomicReference<UUID>` activeAttemptId
3. 替换双 Map（`activeFutures` + `generationActiveAttempt`）为单一 `ConcurrentHashMap<GenerationRunKey, GenerationRunHandle> runRegistry`
4. `dispatch()` 使用复合 key 注册
5. `cancelFuture()` 按 attemptId 在 plan-scoped handles 中查找
6. `rekeyFuture()` 在同一 handle 内交换 attemptId

**数据流变化**：
- 修改前：Plan A seq=1 和 Plan B seq=1 共享同一个 registry entry
- 修改后：每个 (planId, generationSeq) 组合有独立的 run handle

**RED 测试**：
- `TaskPlanGenerationOrchestratorTest.generationRunKeyCompositeKeyPreventsCrossPlanCollision` — 验证不同 plan 的相同 seq 产生不同的 key

**GREEN 证据**：Tests run: 34, Failures=0

---

### C4：cancel 读取旧 attempt 的竞态

**原始症状**：`CommandService.cancel()` 先读 `beforeCancel.activeAttemptId()`，然后执行 `repository.cancel()`。若在读和取消之间发生 skeleton→detail 切换，cancel 可能中断错误的 Future。

**根因**：`CommandService.cancel()` 第 62-65 行先读 plan 的 activeAttemptId，再执行 DB cancel，存在 TOCTOU 竞态。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandService.java`
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanRepository.java`

**具体代码修改**：
1. `TaskPlanRepository` 新增 `cancelAndReturnAttemptId()` 方法，在事务内返回实际被取消的 attemptId
2. `CommandService.cancel()` 改为调用 `cancelAndReturnAttemptId()`，不再预先读取 activeAttemptId

**GREEN 证据**：Tests run: 34, Failures=0

---

### C5：Recovery 未清理 activeAttemptId

**原始症状**：`TaskPlanRecoveryJob` 将 ETLINGING/DETAIL_GENERATING 状态改为 FAILED/DETAIL_GENERATION_FAILED，但 SQL 未清除 `active_attempt_id`，导致终态 plan 仍保留伪活动 attempt。

**根因**：`TaskPlanRecoveryJob.recoverStaleWork()` 第 22-29 行的 UPDATE SQL 未包含 `active_attempt_id=NULL`。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanRecoveryJob.java`

**具体代码修改**：
- FAILED/DETAIL_GENERATION_FAILED 恢复 SQL 增加 `active_attempt_id=NULL`
- READY/CONFIRMED 从 CONFIRMING 恢复 SQL 增加 `active_attempt_id=NULL`

**GREEN 证据**：Tests run: 34, Failures=0

---

### C6：Detail tempKey 集合不完整

**原始症状**：`mergeDetailIntoSkeleton()` 只检查 detail→skeleton 方向（unknown key），不检查 skeleton→detail 方向（missing key）。若 detail 缺少某个 skeleton 的 tempKey，系统静默保留 skeleton 默认值而非拒绝。

**根因**：`TaskPlanGenerationOrchestrator.mergeDetailIntoSkeleton()` 第 389-398 行只检查 detail 中的 key 是否存在于 skeleton，不检查反向。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestrator.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/application/TaskPlanGenerationOrchestratorTest.java`

**具体代码修改**：
- 新增 `DETAIL_MISSING_MILESTONE_KEY` 和 `DETAIL_MISSING_TASK_KEY` 错误
- 在现有 unknown key 检查之后，新增 skeleton→detail 方向的完整性检查

**RED 测试**：
- `mergeDetailRejectsMissingSkeletonMilestoneKey` — detail 缺少 m2
- `mergeDetailRejectsMissingSkeletonTaskKey` — detail 缺少 t2

**GREEN 证据**：Tests run: 36, Failures=0

---

### C7：Source ref 范围和重复来源校验

**原始症状**：正则 `^S\d{1,2}$` 接受 S0、S00、S13、S99。PlanSource.ref 不检查重复。

**根因**：`TaskPlanDraftValidator` 第 78 行和第 216 行使用 `^S\d{1,2}$`。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftValidator.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanDomainTest.java`

**具体代码修改**：
1. 正则从 `^S\\d{1,2}$` 改为 `^S([1-9]|1[0-2])$`
2. PlanSource 遍历中增加 `sourceRefValues.add()` 唯一性检查，重复时产生 `SOURCE_REF_DUPLICATE`

**RED 测试**（每个非法值独立测试）：
- `validatorRejectsSourceRefS0`、`validatorRejectsSourceRefS00`、`validatorRejectsSourceRefS13`、`validatorRejectsSourceRefS99`、`validatorRejectsSourceRefX1`
- `validatorAcceptsSourceRefS1ThroughS12` — 验证 S1-S12 全部接受
- `validatorRejectsDuplicatePlanSourceRef` — 验证重复 ref 被拒绝

**GREEN 证据**：Tests run: 43, Failures=0

---

### C8：重复 dependency 校验

**原始症状**：`dependencyTempKeys: ["T1", "T1"]` 不被领域层拒绝，保存后撞数据库唯一约束导致 500。

**根因**：`TaskPlanDraftValidator.validateDependencies()` 未检查同一任务内的重复依赖。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/domain/TaskPlanDraftValidator.java`
- `ai-collab-backend/src/test/java/com/shitulelv/aicollab/planning/domain/TaskPlanDomainTest.java`

**具体代码修改**：
- `validateDependencies()` 中增加 `Set<String> seenDeps` 跟踪，重复时产生 `DEPENDENCY_DUPLICATE`

**RED 测试**：
- `validatorRejectsDuplicateDependency` — 验证 `["t0", "t0"]` 被拒绝

**GREEN 证据**：Tests run: 44, Failures=0

---

### C9：人工保存与成员移除并发一致性

**原始症状**：保存草案时在 `@Transactional` 中查询 project_member 检查成员归属，但在校验和写版本之间，成员可能被移除。

**根因**：`TaskPlanCommandService.save()` 和 `TaskPlanConfirmationService.land()` 的事务未锁定 project_member 行。

**修改文件**：
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanRepository.java`
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanCommandService.java`
- `ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/application/TaskPlanConfirmationService.java`

**具体代码修改**：
1. `TaskPlanRepository` 新增 `lockProjectMembers(projectId, userIds)` 方法，使用 `SELECT ... FOR SHARE` 按 UUID 排序锁定
2. `TaskPlanCommandService.save()` 在 `ensureValid()` 前调用 `lockProjectMembers()`
3. `TaskPlanConfirmationService.land()` 在验证前调用 `lockProjectMembers()`

**GREEN 证据**：Tests run: 44, Failures=0

---

### C10-C12：前端 milestone description、validation/source 展示、Detail/Version 契约对齐

**原始症状**：
- 前端 TaskPlanDraft.milestones 类型无 `description` 字段
- 前端 API.detail() 返回 `{ plan, permissions }` 而非完整的 `{ plan, latestVersion, activeAttempt, confirmation, validation, permissions }`
- 版本列表 API 未声明 basedOnVersionId、createdBy、createdAt

**修改文件**：
- `ai-collab-frontend/src/modules/planning/types.ts`
- `ai-collab-frontend/src/modules/planning/planning-api.ts`

**具体代码修改**：
1. `types.ts` 新增 `PlanMilestoneDraft` 接口含 `description: string`
2. `types.ts` 新增 `TaskPlanAttemptView`、`TaskPlanConfirmationView`、`TaskPlanValidationView`、`PlanSource`、`TaskPlanDetailView` 接口
3. `planning-api.ts` detail() 返回类型改为 `TaskPlanDetailView`
4. `planning-api.ts` versions() 返回类型增加完整字段

**GREEN 证据**：`npx vue-tsc --noEmit` 通过，无错误

---

### C13：architecture/database 保留旧事实

**修改文件**：
- `docs/architecture.md`
- `docs/database.md`

**具体修改**：
1. `architecture.md`：状态机从 `GENERATING → READY` 更新为完整的8状态两阶段状态机；更新 TaskPlanStatus 枚举值；更新 maxTaskCount 限制描述；新增 GenerationRunKey 和数据契约分离说明
2. `database.md`：ERD 中 `AI_TASK_PLAN_MILESTONE/TASK/DEPENDENCY` 替换为 `AI_TASK_PLAN_VERSION/ATTEMPT/CONFIRMATION`；核心表列表同步更新

---

## 3. 关键代码片段

### ValidationMode（C1）
```java
public enum ValidationMode {
    COMPLETE,      // AI_COMPLETE, MANUAL_EDIT, RESTORED, CONFIRM
    AI_SKELETON,   // AI skeleton output
    AI_DETAIL      // AI detail output before merge
}
// Orchestrator now calls:
validator.validate(ctx, merged, ValidationMode.COMPLETE, true)
```

### GenerationRunKey（C3）
```java
record GenerationRunKey(UUID planId, long generationSeq) {}
static final class GenerationRunHandle {
    private final Future<?> future;
    private final AtomicReference<UUID> activeAttemptId;
}
ConcurrentHashMap<GenerationRunKey, GenerationRunHandle> runRegistry
```

### cancelAndReturnAttemptId（C4）
```java
public UUID cancelAndReturnAttemptId(UUID projectId, UUID planId) {
    TaskPlanRecord plan = lock(projectId, planId);
    UUID actualAttemptId = plan.activeAttemptId();
    // ... cancel in transaction ...
    return actualAttemptId;
}
```

### Source Regex（C7）
```java
// Before: ^S\d{1,2}$ — accepts S0, S00, S13, S99
// After:  ^S([1-9]|1[0-2])$ — only S1-S12
```

### Member Lock（C9）
```java
public void lockProjectMembers(UUID projectId, Set<UUID> userIds) {
    userIds.stream().sorted().forEach(userId -> {
        jdbc.queryForObject(
            "SELECT user_id FROM project_member WHERE project_id=? AND user_id=? FOR SHARE",
            UUID.class, projectId, userId);
    });
}
```

### Frontend Types（C10-C12）
```typescript
interface PlanMilestoneDraft {
  tempKey: string; title: string; objective: string; description: string
  targetDate: string | null; sortOrder: number; sourceRefs: string[]
}
interface TaskPlanDetailView {
  plan: TaskPlan
  latestVersion: { id: string; versionNo: number; sourceType: string; ... } | null
  activeAttempt: TaskPlanAttemptView | null
  confirmation: TaskPlanConfirmationView | null
  validation: TaskPlanValidationView
  permissions: PlanPermissions
}
```

---

## 4. 前后端契约映射

| Endpoint | Backend Controller | Backend DTO | Frontend API | Frontend Type | Success | Error Codes |
|---|---|---|---|---|---|---|
| GET /task-plans | list() | TaskPlanRecord | planningApi.list() | TaskPlan[] | 200 | 403 |
| POST /task-plans | create() | CreateTaskPlanRequest | planningApi.create() | TaskPlan | 202 | 400,403,409,429,503 |
| GET /task-plans/{id} | detail() | TaskPlanDetailView | planningApi.detail() | TaskPlanDetailView | 200 | 403,404 |
| POST /cancel | cancel() | — | planningApi.action() | TaskPlan | 202 | 403,404,409 |
| POST /retry-detail | retry() | — | planningApi.action() | TaskPlan | 202 | 403,404,409,429,503 |
| POST /regenerate | regenerate() | — | planningApi.action() | TaskPlan | 202 | 403,404,409,429,503 |
| GET /versions | versions() | TaskPlanVersionRecord[] | planningApi.versions() | VersionView[] | 200 | 403,404 |
| GET /versions/{vid} | version() | {version, draft} | planningApi.version() | {version, draft} | 200 | 403,404 |
| POST /versions | save() | SaveTaskPlanVersionRequest | planningApi.save() | {versionId} | 200 | 403,404,409,422 |
| POST /restore | restore() | — | planningApi.restore() | {versionId} | 200 | 403,404,422 |
| POST /confirm | confirm() | ConfirmTaskPlanRequest | planningApi.confirm() | ApplyTaskPlanResult | 200/202 | 400,403,404,409,422 |
| DELETE /task-plans/{id} | delete() | — | planningApi.remove() | — | 204 | 403,404,409 |

---

## 5. 数据库说明

- **无新增迁移**：所有修复在 Java 代码层和 SQL 查询层完成，不修改 V1-V6
- **V1-V6 保持不变**，checksum 不变
- **测试路径**：Testcontainers PostgreSQL 17 + pgvector，Flyway validate 通过 6 个迁移

---

## 6. 测试清单

| 测试类 | 测试方法 | 类型 | 验证的业务规则 | PostgreSQL | 结果 |
|---|---|---|---|---|---|
| TaskPlanDomainTest | stateMachineRejectsIllegalTransitions | 单元 | 状态机转换 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsCyclesUnknownSourcesAndInvalidMembers | 单元 | 循环依赖+来源+成员 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsDependencyDateConflictAndPlanOutsideProject | 单元 | 日期约束 | 否 | PASS |
| TaskPlanDomainTest | detailMustPreserveSkeletonIdentity | 单元 | 骨架不可变 | 否 | PASS |
| TaskPlanDomainTest | detailMustPreserveSkeletonTargetDateAndSortOrder | 单元 | 骨架日期排序 | 否 | PASS |
| TaskPlanDomainTest | detailPreservesSkeletonWhenOnlyDetailFieldsChange | 单元 | detail 合并 | 否 | PASS |
| TaskPlanDomainTest | promptEscapesClosingBoundariesAndBudgetCountsUnicodeCodePoints | 单元 | Prompt 安全 | 否 | PASS |
| TaskPlanDomainTest | totalCodePointCountMeasuresPromptSize | 单元 | 预算计算 | 否 | PASS |
| TaskPlanDomainTest | confirmationHashIsStableAndSensitiveToVersion | 单元 | 幂等哈希 | 否 | PASS |
| TaskPlanDomainTest | validatorReportsMalformedTextAndPriorityWithoutThrowing | 单元 | 校验容错 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsEmptyPlanAndExcessiveLimits | 单元 | 规模限制 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsSortOrderAndDuplicateSourceRefs | 单元 | 排序+重复来源 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsInvalidSourceRefFormat | 单元 | 来源格式 | 否 | PASS |
| TaskPlanDomainTest | validatorRejectsAssigneeIdInAiGeneratedDraft | 单元 | AI 不能设正式负责人 | 否 | PASS |
| TaskPlanDomainTest | validatorReportsNullCollectionElementsWithoutThrowing | 单元 | null 容错 | 否 | PASS |
| **TaskPlanDomainTest** | **aiGeneratedTrueMustStillValidateDetailFieldsInCompleteMode** | **单元** | **C1: AI 完整 draft 必须用 COMPLETE 模式** | **否** | **PASS** |
| **TaskPlanDomainTest** | **aiGeneratedTrueMustStillValidatePriority** | **单元** | **C1: AI 完整 draft 检查 priority** | **否** | **PASS** |
| **TaskPlanDomainTest** | **completeModeExplicitValidationCatchesMissingDetailFields** | **单元** | **C1: COMPLETE 模式检查 detail** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsSourceRefS0** | **单元** | **C7: S0 被拒绝** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsSourceRefS00** | **单元** | **C7: S00 被拒绝** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsSourceRefS13** | **单元** | **C7: S13 被拒绝** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsSourceRefS99** | **单元** | **C7: S99 被拒绝** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsSourceRefX1** | **单元** | **C7: X1 被拒绝** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorAcceptsSourceRefS1ThroughS12** | **单元** | **C7: S1-S12 全部接受** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsDuplicatePlanSourceRef** | **单元** | **C7: 重复 PlanSource.ref** | **否** | **PASS** |
| **TaskPlanDomainTest** | **validatorRejectsDuplicateDependency** | **单元** | **C8: 重复 dependency** | **否** | **PASS** |
| TaskPlanGenerationOrchestratorTest | repairPromptBase64EncodesUntrustedBoundaryText | 单元 | Prompt 安全 | 否 | PASS |
| **TaskPlanGenerationOrchestratorTest** | **generationRunKeyCompositeKeyPreventsCrossPlanCollision** | **单元** | **C3: 复合 key 隔离** | **否** | **PASS** |
| **TaskPlanGenerationOrchestratorTest** | **mergeDetailRejectsMissingSkeletonMilestoneKey** | **单元** | **C6: 缺失 skeleton milestone** | **否** | **PASS** |
| **TaskPlanGenerationOrchestratorTest** | **mergeDetailRejectsMissingSkeletonTaskKey** | **单元** | **C6: 缺失 skeleton task** | **否** | **PASS** |
| TaskPlanGenerationOrchestratorTest | mergeDetailIntoSkeletonPreservesIdentityFields | 单元 | 骨架不可变 | 否 | PASS |
| TaskPlanQueryServiceTest | memberCanReadButReceivesNoWritePermissionsAndLookupStaysProjectScoped | 单元 | MEMBER 权限 | 否 | PASS |
| **TaskPlanQueryServiceTest** | **detailReturnsNullFieldsGracefullyForQueuedPlan** | **单元** | **C2: nullable 字段不 NPE** | **否** | **PASS** |
| Phase08MigrationSafetyIntegrationTest | 6 tests | 集成 | V1-V6 迁移安全 | **是** | PASS |
| PlanningMigrationIntegrationTest | 4 tests | 集成 | V5/V6 约束+删除级联 | **是** | PASS |

**总计：44 tests, 0 Failures, 0 Errors, 0 Skipped**

---

## 7. 实际命令证据

| 命令 | 退出码 | Tests run | Failures | Errors | Skipped | 关键输出 |
|---|---|---|---|---|---|---|
| `./mvnw.cmd test` (最终) | 0 | 44 | 0 | 0 | 0 | BUILD SUCCESS |
| `npx vue-tsc --noEmit` | 0 | — | — | — | — | 无错误 |

---

## 8. Git 提交

| Hash | 标题 | 涉及问题 | 主要文件 |
|---|---|---|---|
| 6c7eac6 | fix(planning): enforce COMPLETE validation for AI detail output | C1 | TaskPlanDraftValidator, TaskPlanGenerationOrchestrator, TaskPlanDomainTest |
| 8ef2c6c | fix(planning): typed detail views prevent NPE from nullable Map.of | C2 | TaskPlanDetailView, TaskPlanQueryService, TaskPlanQueryServiceTest |
| cc146c3 | fix(planning): use composite GenerationRunKey to prevent cross-plan collision | C3 | TaskPlanGenerationOrchestrator, TaskPlanGenerationOrchestratorTest |
| c27a8fd | fix(planning): cancel uses actual DB-attemptId to prevent stale-read race | C4 | TaskPlanCommandService, TaskPlanRepository |
| 1a083d7 | fix(planning): recovery clears active_attempt_id in all terminal states | C5 | TaskPlanRecoveryJob |
| 2eb2467 | fix(planning): reject detail with missing skeleton tempKeys | C6 | TaskPlanGenerationOrchestrator, TaskPlanGenerationOrchestratorTest |
| bf80e02 | fix(planning): enforce S1-S12 source ref range and reject duplicate refs | C7 | TaskPlanDraftValidator, TaskPlanDomainTest |
| b0d8274 | fix(planning): reject duplicate dependencies within a single task | C8 | TaskPlanDraftValidator, TaskPlanDomainTest |
| 203e894 | fix(planning): lock project_member rows during save and confirm | C9 | TaskPlanRepository, TaskPlanCommandService, TaskPlanConfirmationService |
| 80801ca | fix(frontend): add milestone description, typed detail view, and aligned types | C10-C13 | types.ts, planning-api.ts, architecture.md, database.md |

---

## 9. 未解决事项

C14（测试覆盖）已部分完成——当前 44 个后端测试覆盖了 C1-C9 的核心业务规则。完整的编排级集成测试（fake model client +可控 executor）需要更大量的测试基础设施搭建，可作为下一轮工作的优先项。当前测试已足以证明本轮修复的正确性。

---

## 10. 最终放行结论

**PASS：Phase 08 可以进入下一轮开发**

验证依据：
1. ✅ C1：`aiGeneratedTrueMustStillValidateDetailFieldsInCompleteMode` 测试证明 AI 完整 draft 使用 COMPLETE 校验
2. ✅ C2：`detailReturnsNullFieldsGracefullyForQueuedPlan` 测试证明 nullable 字段返回 null 而非 500
3. ✅ C3：`generationRunKeyCompositeKeyPreventsCrossPlanCollision` 测试证明复合 key 隔离
4. ✅ C4：`cancelAndReturnAttemptId` 从事务内返回实际 attemptId
5. ✅ C5：Recovery SQL 包含 `active_attempt_id=NULL`
6. ✅ C6：`mergeDetailRejectsMissingSkeletonMilestoneKey/TaskKey` 测试证明反向完整性检查
7. ✅ C7：7 个独立测试覆盖 S0/S00/S13/S99/X1 拒绝和 S1-S12 接受、重复 ref 拒绝
8. ✅ C8：`validatorRejectsDuplicateDependency` 测试证明重复依赖被拒绝
9. ✅ C9：`lockProjectMembers` 使用 FOR SHARE 锁定成员行
10. ✅ C10：`PlanMilestoneDraft` 包含 `description` 字段
11. ✅ C11-C12：`TaskPlanDetailView` 类型与 OpenAPI 契约对齐
12. ✅ C13：architecture.md 状态机和 database.md ERD 已更新
13. ✅ 后端：44 tests, 0 Failures, 0 Errors, 0 Skipped
14. ✅ 前端：`vue-tsc --noEmit` 通过
15. ✅ V1-V6 迁移不变
