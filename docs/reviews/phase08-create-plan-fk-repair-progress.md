# Phase 08：active_attempt 外键修复进度

## 复现

**分支**：`feat/phase-08-ai-task-planning`

**约束定义**：

```sql
ALTER TABLE ai_task_plan
    ADD CONSTRAINT fk_ai_task_plan_active_attempt
        FOREIGN KEY (active_attempt_id) REFERENCES ai_task_plan_attempt(id) ON DELETE SET NULL;
```

**异常链**：

```
DataIntegrityViolationException
  → PSQLException: ERROR: insert or update on table "ai_task_plan" violates foreign key constraint "fk_ai_task_plan_active_attempt"
  → Key (active_attempt_id)=(...) is not present in table "ai_task_plan_attempt"
  → at TaskPlanRepository.create(TaskPlanRepository.java:39)
```

## 修复

**文件**：`ai-collab-backend/src/main/java/com/shitulelv/aicollab/planning/infrastructure/TaskPlanRepository.java`

**变更**：`create()` 方法从 2 步 INSERT 改为 3 步：

1. INSERT plan（不含 active_attempt_id）
2. INSERT attempt
3. 条件 UPDATE plan SET active_attempt_id

## 测试结果

**目标测试**：

```powershell
.\mvnw.cmd -Dtest=TaskPlanRepositoryIntegrationTest test
```

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**完整回归**：

```powershell
.\mvnw.cmd clean package
```

```
Tests run: 67, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 页面验证

已在最终阶段使用真实后端、PostgreSQL、Vite 和 Microsoft Edge 完成页面与 Network 验证；
结果见 `phase08-final-system-repair-report.md`。
