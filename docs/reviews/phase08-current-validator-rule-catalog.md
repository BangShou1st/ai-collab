# Phase 08 现有 Validator 规则清单

> 基线 commit: `12d9714` (feat/phase-08-editable-degradation)
> 审计日期: 2026-07-27
> 审计来源: `TaskPlanDraftValidator.java`, `TaskPlanOutputParser.java`, `ModelOutputContractException.java`, `TaskPlanGenerationOrchestrator.java`

---

## 1. ValidationMode 枚举

| 值 | 用途 | aiGenerated 参数 |
|---|---|---|
| `COMPLETE` | AI_COMPLETE / MANUAL_EDIT / RESTORED / CONFIRM | 可选 |
| `AI_SKELETON` | 骨架阶段输出 | 可选 |
| `AI_DETAIL` | 细节阶段补全（未实际使用，合并后用 COMPLETE + aiGenerated=true） | 可选 |

---

## 2. 结构性错误码（来自 TaskPlanOutputParser）

| code | 产生位置 | 严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|
| `JSON_SYNTAX_INVALID` | `parseSkeleton` / `parseDetail` | HARD | 否 | 否 | N/A |
| `UNKNOWN_PROPERTY` | `parseSkeleton` / `parseDetail`（FAIL_ON_UNKNOWN_PROPERTIES） | HARD | 否 | 否 | N/A |
| `MISSING_REQUIRED_FIELD` | `validateDetailRequiredFields` / `validateDetailRequiredFieldsExist` | HARD | 否 | 否 | N/A |
| `INVALID_FIELD_TYPE` | `parseSkeleton` / `parseDetail`（Cannot deserialize） | HARD | 否 | 否 | N/A |

---

## 3. 领域校验错误码（来自 TaskPlanDraftValidator.validate）

### 3.1 规划级错误

| code | ValidationMode | field | targetType | 当前严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|---|---|
| `PLAN_DATE_OUTSIDE_PROJECT` | ALL | planStartDate, planDueDate | PLAN | HARD | 否 | 否 | N/A |
| `SUMMARY_REQUIRED` | ALL | summary | PLAN | HARD | 否 | 否 | N/A |
| `SUMMARY_TOO_LONG` | ALL | summary | PLAN | HARD | 是（trim） | 否 | summary |
| `ASSUMPTION_LIMIT_EXCEEDED` | ALL | assumptions | PLAN | HARD | 否 | 否 | N/A |
| `RISK_LIMIT_EXCEEDED` | ALL | risks | PLAN | HARD | 否 | 否 | N/A |

### 3.2 里程碑级错误

| code | ValidationMode | field | targetType | 当前严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|---|---|
| `MILESTONE_LIMIT_EXCEEDED` | ALL | — | PLAN | HARD | 否 | 否 | N/A |
| `MILESTONE_REQUIRED` | ALL | — | PLAN | HARD | 否 | 否 | N/A |
| `MILESTONE_NULL` | ALL | — | MILESTONE | HARD | 否 | 否 | N/A |
| `MILESTONE_TEXT_INVALID` | ALL | tempKey, title, objective | MILESTONE | HARD | 是（trim） | 否 | title, objective |
| `MILESTONE_DATE_OUTSIDE_PLAN` | ALL (非 SKELETON) | targetDate | MILESTONE | HARD | 否 | 是 | targetDate |
| `SORT_ORDER_INVALID` | ALL | sortOrder | MILESTONE/TASK | HARD | 否 | 是 | sortOrder |
| `TEMP_KEY_DUPLICATE` | ALL | tempKey | MILESTONE/TASK | HARD | 否 | 否 | N/A |
| `SOURCE_REF_LIMIT_EXCEEDED` | ALL (非 SKELETON) | sourceRefs | MILESTONE | HARD | 否 | 是 | sourceRefs |
| `MILESTONE_REF_INVALID` | ALL | milestoneTempKey | TASK | HARD | 否 | 否 | N/A |

### 3.3 任务级错误

| code | ValidationMode | field | targetType | 当前严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|---|---|
| `TASK_LIMIT_EXCEEDED` | ALL | — | PLAN | HARD | 否 | 否 | N/A |
| `TASK_REQUIRED` | ALL | — | PLAN | HARD | 否 | 否 | N/A |
| `TASK_NULL` | ALL | — | TASK | HARD | 否 | 否 | N/A |
| `TASK_TEXT_INVALID` | ALL | tempKey, milestoneTempKey, title, objective, description | TASK | HARD | 是（trim） | 否 | title, objective, description |
| `TASK_DATE_INVALID` | COMPLETE | startDate, dueDate | TASK | HARD | 否 | 是 | startDate, dueDate |
| `ESTIMATED_HOURS_INVALID` | COMPLETE | estimatedHours | TASK | HARD | 否 | 是 | estimatedHours |
| `TASK_PRIORITY_INVALID` | COMPLETE | priority | TASK | HARD | 是（大小写标准化） | 是 | priority |
| `ASSIGNEE_NOT_PROJECT_MEMBER` | COMPLETE | suggestedAssigneeId, assigneeId | TASK | HARD | 否 | 是 | suggestedAssigneeId |
| `AI_GENERATED_ASSIGNEE_NOT_ALLOWED` | COMPLETE + aiGenerated | assigneeId | TASK | HARD | 否 | 否 | N/A |
| `DEPENDENCY_LIMIT_EXCEEDED` | ALL | dependencyTempKeys | TASK | HARD | 否 | 是 | dependencyTempKeys |
| `SOURCE_REF_LIMIT_EXCEEDED` | ALL (非 SKELETON) | sourceRefs | TASK | HARD | 否 | 是 | sourceRefs |
| `SOURCE_REF_INVALID` | ALL | sourceRefs | TASK | HARD | 否 | 是 | sourceRefs |
| `SOURCE_REF_FORMAT_INVALID` | ALL | sourceRefs | TASK | HARD | 否 | 否 | N/A |

### 3.4 依赖错误

| code | ValidationMode | field | targetType | 当前严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|---|---|
| `DEPENDENCY_REF_INVALID` | ALL | dependencyTempKeys | TASK | HARD | 否 | 是 | dependencyTempKeys |
| `SELF_DEPENDENCY` | ALL | dependencyTempKeys | TASK | HARD | 否 | 是 | dependencyTempKeys |
| `DEPENDENCY_DUPLICATE` | ALL | dependencyTempKeys | TASK | HARD | 是（去重） | 是 | dependencyTempKeys |
| `DEPENDENCY_DATE_CONFLICT` | COMPLETE | startDate, dueDate | TASK | HARD | 否 | 是 | startDate, dueDate |
| `DEPENDENCY_CYCLE` | ALL | dependencyTempKeys | TASK | HARD | 否 | 是 | dependencyTempKeys |

### 3.5 来源错误

| code | ValidationMode | field | targetType | 当前严重性 | 是否可安全归一化 | 是否允许可编辑降级 | Repair 可修改字段 |
|---|---|---|---|---|---|---|---|
| `SOURCE_LIMIT_EXCEEDED` | ALL | sources | PLAN | HARD | 否 | 否 | N/A |
| `SOURCE_INVALID` | ALL | sources | PLAN | HARD | 否 | 否 | N/A |
| `SOURCE_REF_DUPLICATE` | ALL | sources | PLAN | HARD | 否 | 否 | N/A |

---

## 4. 警告码

| code | ValidationMode | field | targetType | 说明 |
|---|---|---|---|---|
| `DUPLICATE_TITLE` | ALL | title | MILESTONE/TASK | 规划内标题标准化后重复 |
| `EXISTING_TITLE_SIMILAR` | ALL | title | MILESTONE/TASK | 与已有项目任务/里程碑重名 |
| `TASK_UNASSIGNED` | COMPLETE | assigneeId | TASK | 最终负责人未指定 |
| `AI_SUGGESTION_WITHOUT_SOURCE` | COMPLETE | sourceRefs | TASK | AI 建议无来源依据 |

---

## 5. 骨架保护错误码（来自 Orchestrator.validateSkeletonPreserved）

| code | 说明 |
|---|---|
| `SKELETON_MUTATED` | detail 阶段修改了骨架身份字段（tempKey/title/objective/targetDate/sortOrder/summary/assumptions/risks 或增删里程碑/任务） |

---

## 6. Detail 合并错误码（来自 Orchestrator.mergeDetailIntoSkeleton）

| code | 说明 |
|---|---|
| `DETAIL_DUPLICATE_MILESTONE_KEY` | detail 里程碑 tempKey 重复 |
| `DETAIL_DUPLICATE_TASK_KEY` | detail 任务 tempKey 重复 |
| `DETAIL_UNKNOWN_MILESTONE_KEY` | detail 里程碑 key 不在骨架中 |
| `DETAIL_UNKNOWN_TASK_KEY` | detail 任务 key 不在骨架中 |
| `DETAIL_MISSING_MILESTONE_KEY` | 骨架里程碑 key 缺失于 detail |
| `DETAIL_MISSING_TASK_KEY` | 骨架任务 key 缺失于 detail |

---

## 7. 当前缺失的关键能力

> 以下能力在当前代码中 **不存在**，需要在 Task 2+ 中建立。

1. **ValidationIssueSeverity 枚举** — 当前只有 flat `List<String> errorCodes`，无严重性分级
2. **StructuredValidationIssue** — 当前无 target/field/relatedKey/safeDetails 定位能力
3. **ValidationIssueCatalog** — 无集中分类目录
4. **ValidationAssessment** — 无 `hasHardIssues()` / `ready()` 等决策方法
5. **BLOCKING_EDITABLE 严重性** — 所有错误当前均为硬错误，无"可编辑降级"路径
6. **REPAIRING / READY_WITH_ISSUES 状态** — 状态机中不存在
7. **GenerationOutcomeDecider** — 决策逻辑散落在 Orchestrator 各 catch 块中
8. **TaskPlanDraftNormalizer** — 无安全归一化组件
9. **TaskPlanRepairPatch** — 无定向修复 patch 契约
10. **ai_task_plan_validation_issue 表** — 无持久化 issues
11. **ai_task_plan_event 表** — 无事件审计
12. **用户编辑 API (PATCH)** — 当前只有 POST versions（整版保存）
13. **局部重新生成 API** — 不存在
14. **确认落地限制** — 当前 READY_WITH_ISSUES 不存在，确认无 issue 检查

---

## 8. 当前状态机

```
SKELETON_GENERATING → DETAIL_GENERATING → READY → CONFIRMING → CONFIRMED
SKELETON_GENERATING → FAILED
DETAIL_GENERATING → DETAIL_GENERATION_FAILED
DETAIL_GENERATION_FAILED → DETAIL_GENERATING | SKELETON_GENERATING
READY → SKELETON_GENERATING
CONFIRMING → CONFIRMED | READY
FAILED, CANCELED → SKELETON_GENERATING
```

**目标状态机（Task 6）：**

```
SKELETON_GENERATING → DETAIL_GENERATING → REPAIRING → READY | READY_WITH_ISSUES | FAILED
```

---

## 9. 前端当前能力

- `PlanStatus` 类型：无 `READY_WITH_ISSUES`
- `TaskPlanValidationView`：仅 `{ errors: string[], warnings: string[] }`，无结构化 issues
- `PlanPermissions`：`canEdit` 基于 `write && ready`，无 `canPartialRegenerate`
- 确认按钮：基于 `permissions.canConfirm`，无 issue 阻断逻辑
- 编辑：整版 POST，无 PATCH、无乐观锁、无事件时间线
