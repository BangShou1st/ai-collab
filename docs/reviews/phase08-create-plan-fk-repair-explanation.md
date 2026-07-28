# Phase 08：修复 AI 规划创建 500（active_attempt 外键写入顺序）

## 原始症状

- 前端创建 AI 规划后提示：`服务暂时异常，请稍后重试`
- HTTP 状态：`500 INTERNAL_ERROR`
- 后端异常：`DataIntegrityViolationException`
- 业务码：`INTERNAL_ERROR`（非业务异常，进入全局未处理异常处理器）

## 精确根因

数据库存在双向外键依赖：

```
plan.active_attempt_id → ai_task_plan_attempt(id)   (fk_ai_task_plan_active_attempt)
attempt.plan_id → ai_task_plan(id)                  (fk_ai_task_plan_attempt_plan_id)
```

`TaskPlanRepository.create()` 的 INSERT 顺序违反了第一个约束：

```
1. INSERT ai_task_plan，同时 active_attempt_id = attemptId  ← 此时 attempt 行不存在
2. INSERT ai_task_plan_attempt(id = attemptId)
```

第 1 步执行时，PostgreSQL 立即校验 `fk_ai_task_plan_active_attempt`（非 DEFERRABLE），发现 `active_attempt_id` 引用的 attempt 行尚不存在，抛出 `DataIntegrityViolationException`。

该异常不是 `BusinessException`，最终进入 `GlobalExceptionHandler.handleUnexpectedException()`，返回 500。

整个失败发生在 `quota check → repository.create` 阶段，`orchestrator.dispatch()` 和模型 API 根本还没有执行。

## 修改前 SQL 顺序

```sql
-- 步骤 1：INSERT plan，active_attempt_id = attemptId（attempt 行不存在 → FK 违规）
INSERT INTO ai_task_plan(id, ..., active_attempt_id, created_by)
VALUES (?, ..., ?, ?);

-- 步骤 2：INSERT attempt（永远不会执行到）
INSERT INTO ai_task_plan_attempt(id, plan_id, attempt_no, generation_seq, stage, status, created_by)
VALUES (?, ?, 1, 1, 'SKELETON', 'QUEUED', ?);
```

## 修改后 SQL 顺序

```sql
-- 步骤 1：INSERT plan，不设置 active_attempt_id（NULL 满足 FK 约束）
INSERT INTO ai_task_plan(id, project_id, title, goal, constraints, plan_start_date, plan_due_date,
  max_task_count, selected_document_ids_json, status, created_by)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 'SKELETON_GENERATING', ?);

-- 步骤 2：INSERT attempt（plan 已存在，attempt.plan_id FK 满足）
INSERT INTO ai_task_plan_attempt(id, plan_id, attempt_no, generation_seq, stage, status, created_by)
VALUES (?, ?, 1, 1, 'SKELETON', 'QUEUED', ?);

-- 步骤 3：条件 UPDATE，绑定 active_attempt_id（attempt 已存在，plan.active_attempt_id FK 满足）
UPDATE ai_task_plan SET active_attempt_id=?, updated_at=now()
WHERE id=? AND project_id=? AND active_attempt_id IS NULL
  AND generation_seq=1 AND status='SKELETON_GENERATING';
-- 检查 updated == 1，否则抛出 IllegalStateException
```

## 为什么不修改迁移

外键约束 `fk_ai_task_plan_active_attempt` 本身是正确的——它确保 `active_attempt_id` 始终指向一个真实存在的 attempt。错误不在数据库 schema，而在应用层的 INSERT 顺序。修改迁移（删除外键、改为 DEFERRABLE 等）只是掩盖错误，不是修复。

## 测试

| 测试类 | 测试方法 | 修复前异常 | 修复后断言 | 真实 PostgreSQL |
|--------|----------|-----------|-----------|----------------|
| `TaskPlanRepositoryIntegrationTest` | `createPersistsPlanThenAttemptWithoutViolatingActiveAttemptForeignKey` | `DataIntegrityViolationException` | plan/attempt 行存在，互相正确关联，status/stage/attempt_no/generation_seq 正确 | ✓ (Testcontainers pgvector:pg17) |
| `TaskPlanRepositoryIntegrationTest` | `createRollsBackPlanWhenAttemptCreationFails` | trigger 注入失败后无事务回滚 | plan 和 attempt 均无残留行 | ✓ (Testcontainers pgvector:pg17) |
| `TaskPlanRepositoryIntegrationTest` | `createReturnsDispatchableRecord` | `DataIntegrityViolationException` | activeAttemptId 非空，dispatch 被调用 | ✓ (Testcontainers pgvector:pg17) |

测试命令：

```powershell
.\mvnw.cmd -Dtest=TaskPlanRepositoryIntegrationTest test
.\mvnw.cmd clean package
```

结果：Tests run: 67, Failures: 0, Errors: 0, Skipped: 0

## 页面验证

启动后端和前端后，浏览器 Network 中创建规划：

```
POST /api/v1/projects/{projectId}/ai/task-plans
```

预期响应：

```
HTTP 202
code = SUCCESS
data.activeAttemptId 非空
status = SKELETON_GENERATING
```

数据库验证：

```sql
SELECT id, status, active_attempt_id, generation_seq
FROM ai_task_plan ORDER BY created_at DESC LIMIT 1;

SELECT id, plan_id, stage, status, attempt_no, generation_seq
FROM ai_task_plan_attempt ORDER BY created_at DESC LIMIT 1;
```

两行必须互相对应（plan.active_attempt_id = attempt.id，attempt.plan_id = plan.id）。

## Git commit

```
fix(planning): create plan and root attempt in foreign-key-safe order
```

## 未解决事项

无。修复后 POST 创建规划返回 202，plan 和 attempt 互相关联正确。若此后异步模型阶段出现业务失败，应根据准确业务码分类（PLANNING_MODEL_UNAVAILABLE、PLANNING_MODEL_TIMEOUT 等）单独处理。
