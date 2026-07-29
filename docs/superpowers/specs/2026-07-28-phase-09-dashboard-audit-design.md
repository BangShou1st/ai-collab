# Phase 09 — Dashboard + Audit 设计规范

> 日期：2026-07-28 | 状态：已实现并验证（2026-07-29）

## 1. 概述

为项目添加项目概览 Dashboard 和操作日志 Audit 闭环，使用户进入项目后看到整体情况，管理员可审计操作历史。

## 2. 范围

### 包含

- Dashboard 聚合查询接口和页面
- Audit 分页查询接口和页面
- 路由和导航更新
- 审计 detail 增强（高价值事件）

### 不包含

- AI 周报、延期预测、关键路径分析
- 甘特图、日历、依赖图
- WebSocket 实时动态
- Dashboard 自定义组件
- Audit 高级筛选、导出和归档
- 新缓存层

## 3. API 契约

### 3.1 Dashboard

```
GET /api/v1/projects/{projectId}/dashboard
```

返回：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "project": {
      "id": "uuid",
      "name": "string",
      "description": "string",
      "status": "ACTIVE|ARCHIVED",
      "startDate": "2026-01-01",
      "dueDate": "2026-12-31",
      "currentUserRole": "OWNER|ADMIN|MEMBER",
      "memberCount": 5
    },
    "tasks": {
      "total": 20,
      "todo": 8,
      "inProgress": 5,
      "blocked": 1,
      "done": 5,
      "canceled": 1,
      "overdue": 2,
      "completionRate": 0.263
    },
    "milestones": [
      {
        "id": "uuid",
        "name": "string",
        "status": "PLANNED|ACTIVE|COMPLETED|CANCELED",
        "targetDate": "2026-06-30",
        "totalTasks": 5,
        "completedTasks": 2,
        "completionRate": 0.4,
        "overdue": false
      }
    ],
    "recentTasks": [
      {
        "id": "uuid",
        "title": "string",
        "status": "TODO",
        "priority": "HIGH",
        "assigneeId": "uuid",
        "assigneeDisplayName": "string",
        "milestoneId": "uuid",
        "milestoneName": "string",
        "dueDate": "2026-07-30",
        "unfinishedDependencyCount": 1,
        "overdue": true,
        "updatedAt": "2026-07-28T10:00:00Z"
      }
    ],
    "recentDocuments": [
      {
        "id": "uuid",
        "originalFilename": "string",
        "status": "READY",
        "uploadedBy": "uuid",
        "uploaderDisplayName": "string",
        "createdAt": "2026-07-28T10:00:00Z"
      }
    ],
    "recentActivities": [
      {
        "id": "uuid",
        "userDisplayName": "string",
        "action": "TASK_CREATED",
        "entityType": "TASK",
        "entityId": "uuid",
        "summary": "张三创建了任务「实现登录功能」",
        "createdAt": "2026-07-28T10:00:00Z"
      }
    ]
  }
}
```

### 3.2 Audit 分页

```
GET /api/v1/projects/{projectId}/audit-logs?page=1&size=20
```

返回：

```json
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "items": [
      {
        "id": "uuid",
        "userId": "uuid",
        "userDisplayName": "string",
        "action": "TASK_CREATED",
        "entityType": "TASK",
        "entityId": "uuid",
        "detail": {},
        "summary": "张三创建了任务「实现登录功能」",
        "requestId": "uuid",
        "createdAt": "2026-07-28T10:00:00Z"
      }
    ],
    "page": 1,
    "size": 20,
    "total": 150
  }
}
```

分页规则：
- `page` 从 1 开始
- `size` 默认 20，范围 1–100
- 排序：`created_at DESC, id DESC`
- `total` 为 `Long`

## 4. 权限

| 接口 | 角色 | 守卫 |
|---|---|---|
| Dashboard | OWNER/ADMIN/MEMBER | `requireMember` |
| Audit 分页 | OWNER/ADMIN | `requireAdmin` |

前端：MEMBER 不显示操作日志导航。

## 5. 统计语义

- 项目完成率：`DONE / (非 CANCELED 任务数)`，分母为 0 返回 0
- 项目逾期任务：`due_date < CURRENT_DATE AND status NOT IN ('DONE','CANCELED')`
- 里程碑完成率：该里程碑 `DONE / (非 CANCELED 任务数)`，无任务返回 0
- 里程碑逾期：`target_date < CURRENT_DATE AND status NOT IN ('COMPLETED','CANCELED')`
- 最近任务：`updated_at DESC, id DESC`，最多 8 条
- 最近文档：`created_at DESC, id DESC`，最多 5 条
- 最近活动：`created_at DESC, id DESC`，最多 10 条，脱敏
- 阻塞任务：状态 `BLOCKED`
- 最近任务 `unfinishedDependencyCount`：未完成依赖数（独立字段）

## 6. 后端设计

### 新增类

| 类 | 包 | 职责 |
|---|---|---|
| `ProjectDashboardController` | `project.api.controller` | Dashboard 端点 |
| `ProjectDashboardQueryService` | `project.application.service` | Dashboard 聚合查询 |
| `ProjectDashboardMapper` | `project.infrastructure.mapper` | Dashboard SQL |
| `DashboardView` | `project.application.view` | Dashboard 响应 |
| `AuditLogController` | `project.api.controller` | Audit 端点 |
| `AuditLogQueryService` | `project.application.service` | Audit 分页查询 |
| 扩展 `AuditLogMapper` | `project.infrastructure.mapper` | 分页/计数/活动 SQL |
| `AuditLogPageView` | `project.application.view` | Audit 分页响应 |
| `AuditLogItemView` | `project.application.view` | Audit 条目 |
| `AuditSummaryFormatter` | `project.application.service` | 摘要格式化（纯函数） |

### SQL 规则

- 所有项目级 SQL 显式携带 `project_id`
- 使用数据库 `CURRENT_DATE` 计算日期
- 避免全表扫描和 N+1
- 聚合使用 `GROUP BY` 而非 Java 内存统计

## 7. 前端设计

### 新增文件

| 文件 | 职责 |
|---|---|
| `modules/dashboard/DashboardView.vue` | Dashboard 页面 |
| `modules/dashboard/dashboard-api.ts` | Dashboard API |
| `modules/dashboard/types.ts` | Dashboard 类型 |
| `modules/audit/AuditLogView.vue` | Audit 页面 |
| `modules/audit/audit-api.ts` | Audit API |
| `modules/audit/types.ts` | Audit 类型 |
| `stores/project-context-store.ts` | 项目上下文（角色缓存） |

### 修改文件

| 文件 | 修改内容 |
|---|---|
| `router.ts` | 新增 Dashboard、Audit 路由，项目根重定向 |
| `AppShell.vue` | 添加项目概览导航，按角色显示操作日志 |
| `ProjectListView.vue` | "进入项目" 指向 Dashboard |
| `shared/display-labels.ts` | 审计 action/entityType 中文映射 |

## 8. 审计 detail 增强

保留旧 `AuditService.write()` 重载，新增接收 `Map<String, ?>` 的重载。增强以下事件：

- 任务创建/更新/删除/状态变化
- 文档上传/处理完成/删除
- AI 规划确认
- 成员邀请/角色变化/移除

Detail 限制：最大 4096 字符，超出截断。不保存描述全文、文档正文、模型 Prompt、Token、密钥。

历史空 detail：`AuditSummaryFormatter` 提供稳定回退。
