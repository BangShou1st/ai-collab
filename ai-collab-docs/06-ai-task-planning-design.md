# AI 任务规划设计

## 1. 目标

根据项目目标、时间范围、成员和选定文档生成可编辑的里程碑、任务与依赖草案。模型只生成建议，不直接执行写操作。

## 2. 状态机

```text
GENERATING -> READY -> CONFIRMED
     |          |
     v          v
   FAILED     CANCELED
```

- GENERATING：异步调用模型中
- READY：结构化输出通过校验，可编辑
- CONFIRMED：已写入正式任务，不可修改
- FAILED：模型或校验失败，可重新生成
- CANCELED：用户删除未确认草案

## 3. 生成输入

```java
public record GenerateTaskPlanCommand(
    UUID projectId,
    UUID operatorId,
    String goal,
    LocalDate startDate,
    LocalDate dueDate,
    int maxTasks,
    List<UUID> documentIds,
    List<String> constraints
) {}
```

限制：

- `goal` 20～2,000 字符
- 时间范围 1～180 天
- `maxTasks` 5～50，默认 30
- 文档最多 10 个且必须 READY
- 约束最多 10 条，每条 200 字符

## 4. 模型上下文

通过只读工具或预组装上下文提供：

```java
@Tool(description = "查询项目基本信息和起止日期")
ProjectContext getProjectContext(UUID projectId);

@Tool(description = "列出项目成员及角色，不返回密码和邮箱")
List<MemberContext> listProjectMembers(UUID projectId);

@Tool(description = "列出现有里程碑和未完成任务")
ExistingWorkContext listExistingWork(UUID projectId);

@Tool(description = "从选定项目文档中检索相关片段")
List<PlanningSource> searchPlanningSources(UUID projectId, String query);
```

所有工具内部再次执行项目权限校验。工具只读，最多允许 4 次调用。

## 5. 结构化输出

```java
public record TaskPlanDraft(
    String summary,
    List<String> assumptions,
    List<String> risks,
    List<MilestoneDraft> milestones,
    List<TaskDraft> tasks
) {}

public record MilestoneDraft(
    String tempKey,
    String name,
    String description,
    LocalDate targetDate,
    int sortOrder
) {}

public record TaskDraft(
    String tempKey,
    String milestoneTempKey,
    String title,
    String description,
    TaskPriority priority,
    BigDecimal estimateHours,
    LocalDate startDate,
    LocalDate dueDate,
    UUID suggestedAssigneeId,
    List<String> dependencyTempKeys,
    List<UUID> sourceDocumentIds,
    int sortOrder
) {}
```

## 6. JSON Schema 规则

- milestone：1～10 个
- task：5～50 个且不超过用户 `maxTasks`
- `tempKey` 使用 `M1`、`M2`、`T1`、`T2` 格式，在草案中唯一
- 每个任务必须引用存在的 milestone temp key
- 依赖只能引用存在的 task temp key
- 任务不能依赖自身
- 依赖图不能有环
- 日期必须位于规划时间范围内
- 任务开始日期不得晚于截止日期
- 建议负责人必须是当前项目成员，否则置空并添加风险说明
- 单任务预计工时 0.5～80

## 7. 两阶段校验

### 7.1 生成后校验

1. JSON 反序列化。
2. Bean Validation。
3. temp key 引用完整性。
4. 日期范围。
5. 成员归属。
6. 依赖环检测。

失败时：

- 将校验错误摘要附加给模型，最多修复 1 次。
- 第二次仍失败，状态改为 FAILED，错误码 `AI_STRUCTURED_OUTPUT_INVALID`。

### 7.2 确认前校验

用户编辑草案后再次校验全部规则，并检查：

- 项目和成员仍存在。
- 里程碑名称不为空。
- 幂等键未使用或对应同一 plan。
- plan 状态为 READY。

## 8. 确认事务

```text
BEGIN
  lock ai_task_plan
  check status READY
  create milestones and build tempKey -> UUID map
  create tasks and build tempKey -> UUID map
  create dependencies
  update plan status CONFIRMED
  write audit log
  save idempotency result
COMMIT
```

若任一步失败，全部回滚。

## 9. 幂等

- 客户端生成 UUID 作为 `Idempotency-Key`。
- Redis 保存 `userId + endpoint + key` 到结果，TTL 24 小时。
- 同一 key 与同一 plan 重试返回第一次结果。
- 同一 key 对应不同 plan 返回 409 `IDEMPOTENCY_CONFLICT`。
- Redis 不可用时使用数据库唯一记录降级。

## 10. 前端编辑规则

用户可：

- 修改里程碑名称和日期
- 修改任务标题、描述、优先级、负责人和工时
- 添加、删除任务
- 调整依赖
- 查看来源文档和模型假设

确认按钮旁明确提示：“确认后将创建正式数据，无法通过此草案再次应用”。

## 11. 审计

记录生成者、模型、文档列表、草案版本、确认者、新建实体 ID、耗时和 Token。不要将 API Key 或完整文档上下文写入日志。
