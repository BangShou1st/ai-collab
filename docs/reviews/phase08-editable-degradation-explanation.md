# Phase 08 可编辑降级实施说明

> 最终结论: **BLOCKED** — 真实模型验收（场景 A-D）需要手动完成

---

## 状态机

```
SKELETON_GENERATING → DETAIL_GENERATING → REPAIRING → READY | READY_WITH_ISSUES | FAILED
SKELETON_GENERATING → FAILED
DETAIL_GENERATING → DETAIL_GENERATION_FAILED
DETAIL_GENERATION_FAILED → DETAIL_GENERATING | SKELETON_GENERATING
READY → CONFIRMING | SKELETON_GENERATING
READY_WITH_ISSUES → SKELETON_GENERATING
CONFIRMING → CONFIRMED | READY
FAILED, CANCELED → SKELETON_GENERATING
CONFIRMED → (终态)
```

允许操作：
- `READY`: 确认、编辑、重新生成、删除
- `READY_WITH_ISSUES`: 编辑、局部重新生成、重新生成（不能确认）
- `REPAIRING`: (生成中，不可操作)

---

## Issue 分类

| code | severity | 是否 Repair | 是否允许 READY_WITH_ISSUES | 是否阻止确认 |
|---|---|---|---|---|
| TEMP_KEY_DUPLICATE | HARD | 否 | 否 | 是 |
| MILESTONE_REF_INVALID | HARD | 否 | 否 | 是 |
| SKELETON_MUTATED | HARD | 否 | 否 | 是 |
| JSON_SYNTAX_INVALID | HARD | 否 | 否 | 是 |
| UNKNOWN_PROPERTY | HARD | 否 | 否 | 是 |
| PLAN_DATE_OUTSIDE_PROJECT | HARD | 否 | 否 | 是 |
| DEPENDENCY_DATE_CONFLICT | BLOCKING_EDITABLE | 是 | 是 | 是 |
| DEPENDENCY_CYCLE | BLOCKING_EDITABLE | 是 | 是 | 是 |
| SELF_DEPENDENCY | BLOCKING_EDITABLE | 是 | 是 | 是 |
| ASSIGNEE_NOT_PROJECT_MEMBER | BLOCKING_EDITABLE | 是 | 是 | 是 |
| TASK_DATE_INVALID | BLOCKING_EDITABLE | 是 | 是 | 是 |
| ESTIMATED_HOURS_INVALID | BLOCKING_EDITABLE | 是 | 是 | 是 |
| DUPLICATE_TITLE | WARNING | 否 | 是 | 否 |
| TASK_UNASSIGNED | WARNING | 否 | 是 | 否 |
| AI_SUGGESTION_WITHOUT_SOURCE | WARNING | 否 | 是 | 否 |

---

## 安全归一化

### 做了什么
- 字符串 trim
- assumptions/risks/sourceRefs/dependencyTempKeys 去重并保持首次出现顺序
- null 集合转 `List.of()`
- milestone/task 按 `sortOrder` 后按 `tempKey` 稳定排序
- priority 大小写标准化 (LOW/MEDIUM/HIGH/URGENT)
- 空白字符串转 null（仅限本来可空字段）

### 绝不做什么
- 填默认工时
- 填默认日期
- 生成负责人
- 生成来源
- 删除依赖冲突
- 修复环
- 修改 title/objective 业务含义

---

## Prompt 适配

### Skeleton Prompt
- 只包含 plan input + JSON schema
- 不包含 sources、member context
- 所有用户输入 XML 转义

### Detail Prompt
- 包含 PLAN_CONSTRAINTS（日期范围、最大任务数）
- 包含 MEMBER_CONTEXT（仅 UUID/ displayName/role）
- 包含 SOURCES（S1-S12）
- 包含 SKELETON_IDENTITY（只读）
- 包含 BUSINESS_RULES（业务约束）

### Repair Prompt
- 包含结构化 VALIDATION_ISSUES
- 包含 ALLOWED_CHANGES（可修改字段）
- 包含 LOCKED_FIELDS（不可修改字段）
- 只输出 patch JSON

---

## Repair Patch

### Allowed Fields（按 issue 类型）
- DEPENDENCY_DATE_CONFLICT: startDate, dueDate
- ASSIGNEE_NOT_PROJECT_MEMBER: suggestedAssigneeId
- TASK_DATE_INVALID: startDate, dueDate
- DEPENDENCY_CYCLE: dependencyTempKeys

### Locked Fields（始终锁定）
- tempKey, milestoneTempKey, title, objective, sortOrder
- summary, assumptions, risks
- 非目标任务所有字段

---

## 数据库

### 迁移文件
- `V7__add_task_plan_issues_and_events.sql`

### 新表
- `ai_task_plan_validation_issue`: 持久化验证问题
- `ai_task_plan_event`: 审计事件

### 索引
- `idx_issue_plan_version`: (plan_id, version_id)
- `idx_issue_plan_resolved_severity`: (plan_id, resolved, severity)
- `idx_event_plan_created`: (plan_id, created_at)

### 事务边界
- `TaskPlanVersionCommitService.commit()`: 版本 + issues + 事件原子提交
- `TaskPlanCommandService.edit()`: 编辑 + 归一化 + 校验 + 版本创建

---

## 编辑、版本与事件

### 版本来源
- `AI_SKELETON`: 骨架生成
- `AI_COMPLETE`: 完整生成
- `AI_REPAIR`: AI 修复
- `AI_PARTIAL_REPAIR`: 局部修复
- `AI_REGENERATED`: 重新生成
- `USER_EDIT`: 用户编辑
- `RESTORED`: 历史恢复

### 事件类型
- `PLAN_GENERATED`: 规划已生成
- `PLAN_GENERATED_WITH_ISSUES`: 规划已生成（含待解决问题）
- `TASK_PLAN_USER_EDITED`: 用户编辑了规划
- `TASK_PLAN_PARTIAL_REGENERATED`: 局部重新生成
- `TASK_PLAN_CONFIRMED`: 规划已确认

---

## API

### 新增/修改接口
- `PATCH /api/v1/projects/{projectId}/ai/task-plans/{planId}`: 用户编辑
- `POST /api/v1/projects/{projectId}/ai/task-plans/{planId}/partial-regenerate`: 局部重新生成
- `GET /api/v1/projects/{projectId}/ai/task-plans/{planId}/events`: 事件时间线

### 新增错误码
- `TASK_PLAN_HAS_BLOCKING_ISSUES` (409): 规划仍有需要处理的问题

### 权限变更
- `canEdit`: READY 或 READY_WITH_ISSUES
- `canConfirm`: READY 且无 blocking issues
- `canPartialRegenerate`: READY 或 READY_WITH_ISSUES

---

## 测试

| 测试类 | 测试数 | 命令 |
|---|---|---|
| TaskPlanDraftValidatorRuleCatalogTest | 10 | `.\mvnw.cmd -Dtest=TaskPlanDraftValidatorRuleCatalogTest test` |
| ValidationIssueCatalogTest | 19 | `.\mvnw.cmd -Dtest=ValidationIssueCatalogTest test` |
| TaskPlanDraftNormalizerTest | 14 | `.\mvnw.cmd -Dtest=TaskPlanDraftNormalizerTest test` |
| PlanningPromptPolicyTest | 12 | `.\mvnw.cmd -Dtest=PlanningPromptPolicyTest test` |
| TaskPlanRepairPatchTest | 12 | `.\mvnw.cmd -Dtest=TaskPlanRepairPatchTest test` |
| GenerationOutcomeDeciderTest | 16 | `.\mvnw.cmd -Dtest=GenerationOutcomeDeciderTest test` |
| TaskPlanVersionCommitServiceTest | 5 | `.\mvnw.cmd -Dtest=TaskPlanVersionCommitServiceTest test` |
| TaskPlanEditTest | 5 | `.\mvnw.cmd -Dtest=TaskPlanEditTest test` |
| TaskPlanPartialRegenerateTest | 5 | `.\mvnw.cmd -Dtest=TaskPlanPartialRegenerateTest test` |
| Phase08EditableDegradationIntegrationTest | 16 | `.\mvnw.cmd -Dtest=Phase08EditableDegradationIntegrationTest test` |
| TaskPlanQueryServiceTest | 2 | `.\mvnw.cmd -Dtest=TaskPlanQueryServiceTest test` |
| 后端全量 | 207 | `.\mvnw.cmd test` |
| 前端全量 | 31 | `npx vitest run` |

---

## Commit hashes

| Task | Hash | Message |
|---|---|---|
| 1 | `17cc948` | test(planning): freeze current validator rule semantics |
| 2 | `d0ec41a` | feat(planning): add structured validation issue severity and catalog |
| 3 | `4d04417` | feat(planning): add deterministic safe draft normalization |
| 4 | `97ba26e` | feat(planning): adapt prompts to domain rules and model limits |
| 5 | `fed46b0` | feat(planning): apply scoped model repair patches safely |
| 6 | `8474b12` | feat(planning): degrade editable model conflicts without losing drafts |
| 7 | `8853ab8` | feat(planning): persist validation issues and plan events atomically |
| 8 | `3977409` | feat(planning): edit generated plans with versioned audit events |
| 9 | `0503aab` | feat(planning): regenerate selected plan fields with scoped patches |
| 10 | `5e89829` | fix(planning): block confirmation while plan issues remain |
| 11 | `ab8e217` | feat(api): expose structured plan issues and edit operations |
| 12 | `9989ee5` | feat(frontend): edit and repair plans with unresolved issues |
| 13 | `462070a` | test(planning): cover editable degradation and repair end to end |
| 14 | `3dad4f9` | fix(test): update migration safety test for V7 |

---

## 工作区

```
git status --short
git diff --check
```

未跟踪非代码文件：
- ai-collab-backend/src-后端.zip
- ai-collab-frontend/src-前端.zip
- docs/reviews/phase08-create-plan-fk-repair-explanation.md
- docs/reviews/phase08-create-plan-fk-repair-progress.md
- docs/reviews/phase08-detail-domain-validation-explanation.md
- docs/superpowers/plans/2026-07-26-phase-08-repair.md
- fix/
