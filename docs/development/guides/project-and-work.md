# 项目与协作功能实现指南

## 功能边界

本指南覆盖项目、成员、邀请、里程碑、任务、依赖、评论、概览、审计和工作可视化。它不覆盖文档/RAG、AI 规划和 Agent。

核心原则：项目是所有协作数据的安全边界。任何子资源操作都必须先确认当前用户属于项目，再按角色、负责人、状态和版本决定是否允许。

## 当前入口

后端：

- 项目：`project/api/controller/ProjectController.java`
- 项目服务：`project/application/service/ProjectApplicationService.java`
- 成员：`ProjectMemberApplicationService.java`
- 权限：`project/domain/policy/DatabaseProjectAccessGuard.java`
- 项目状态：`ProjectStatusTransitionPolicy.java`
- 里程碑：`work/application/service/MilestoneApplicationService.java`
- 任务：`work/application/service/TaskApplicationService.java`
- 工作权限：`work/domain/policy/WorkPermissionPolicy.java`
- Mapper：`project/infrastructure/mapper/`、`work/infrastructure/mapper/`

前端：

- 项目列表：`modules/project/ProjectListView.vue`
- 成员：`modules/project/ProjectMembersView.vue`
- 看板：`modules/work/TaskBoardView.vue`
- 里程碑：`modules/work/MilestoneView.vue`
- API/type：对应模块内 `*-api.ts`、`types.ts`

## 依赖方向

```text
Controller / Request DTO
  → Application Service / View
  → Domain Policy
  → Repository
  → Mapper / PostgreSQL
```

Controller 不访问 Mapper。页面不绕过模块 API 直接调用 Axios。

## 权限

| 操作 | OWNER | ADMIN | MEMBER |
|---|---:|---:|---:|
| 查看项目数据 | 是 | 是 | 是 |
| 修改项目、删除项目 | 是 | 否 | 否 |
| 管理成员/邀请 | 是 | 部分 | 否 |
| 创建/完整编辑任务 | 是 | 是 | 否 |
| 修改自己负责任务状态 | 是 | 是 | 是 |
| 查看审计 | 是 | 是 | 否 |

先调用 `ProjectAccessGuard.requireMember/requireAdmin/requireOwner`，再读取项目资源。前端入口仅用于体验，不能代替后端授权。

## 项目隔离

正确：

```java
return tasks.find(projectId, taskId)
        .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
```

对应 SQL：

```sql
SELECT ...
FROM project_task
WHERE project_id = #{projectId}
  AND id = #{taskId}
```

禁止先按 `taskId` 全局查询再判断返回对象的 `projectId`。这种写法会扩大越权和误用风险。

## 状态与乐观锁

项目状态：

```text
PREPARING → ACTIVE
ACTIVE → COMPLETED | ARCHIVED
COMPLETED → ACTIVE | ARCHIVED
ARCHIVED → ACTIVE
```

转换只通过 `ProjectStatusTransitionPolicy`。项目、任务和里程碑更新必须提交当前 `version`：

```sql
UPDATE project_task
SET title = #{title},
    version = version + 1,
    updated_at = now()
WHERE project_id = #{projectId}
  AND id = #{taskId}
  AND version = #{version}
```

更新数为 0 时返回 `VERSION_CONFLICT`，前端刷新服务器真相。

## 实现一个任务字段的完整闭环

例如为任务增加一个受控字段：

1. 判断是否需要数据库字段和约束；如需要，创建下一个迁移。
2. 更新 `TaskEntity`、Request DTO、`TaskView`。
3. 更新 Mapper 的 select/insert/update。
4. 在 `TaskApplicationService` 校验权限、范围、状态和版本。
5. 更新 Controller 契约。
6. 更新 OpenAPI schema。
7. 更新 `modules/work/types.ts`、`work-api.ts` 和表单。
8. 写 PostgreSQL 集成测试、权限测试和前端交互测试。

参考请求：

```java
public record UpdateTaskRequest(
        @NotBlank @Size(max = 160) String title,
        @Size(max = 4000) String description,
        @NotNull TaskStatus status,
        @NotNull TaskPriority priority,
        UUID milestoneId,
        UUID assigneeId,
        @DecimalMin("0.5") @DecimalMax("80") BigDecimal estimateHours,
        LocalDate startDate,
        LocalDate dueDate,
        @NotNull @Min(0) Integer version) {
}
```

不要只在 DTO 校验跨字段日期；`startDate <= dueDate` 还需要领域校验和数据库约束。

## 依赖与批量操作

- 任务依赖必须属于同一项目。
- 禁止自依赖和有向环。
- 状态进入进行中/完成前检查未完成依赖。
- 批量更新在一个事务中执行，每个任务都校验项目、权限和版本。
- 批量失败不得留下部分提交。

使用现有 `TaskDependencyPolicy`，不要在 Controller 或 Vue 中复制环检测。

## 审计

高价值写操作通过 `AuditService` 写入：

```java
audit.write(
        projectId,
        actorId,
        "TASK_STATUS_CHANGED",
        "TASK",
        taskId,
        Map.of("from", oldStatus, "to", newStatus));
```

detail 只放短字段、ID 和状态变化；不放完整描述、评论、Token 或 Prompt。新增 action/entity 时同步后端摘要和前端 `display-labels.ts`。

## 测试

必须覆盖：

- OWNER/ADMIN/MEMBER/非成员。
- 跨项目 taskId、milestoneId、assigneeId。
- 合法和非法状态转换。
- 过期 version。
- 依赖环和未完成依赖。
- 批量操作原子性。
- PostgreSQL 约束和稳定排序。
- 前端入口、错误、空状态和中文映射。

## 发给 Claude 的提示

```text
读取 AGENTS.md、docs/development/guides/project-and-work.md 和
docs/development/api-contract-checklist.md。
只实现一个项目/任务闭环；先列出权限矩阵、状态规则、涉及表、接口和测试。
所有子资源查询必须绑定 projectId；所有更新必须说明并发控制。
```

## 可交给 MiMo 的任务

可以：在已有 `types.ts` 中增加一个已确定字段，并同步单个表单控件；接口和 DTO 必须已由 Claude 完成。

禁止：让 MiMo设计任务状态、成员权限、依赖算法、批量事务、迁移或 API。
