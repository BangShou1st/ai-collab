# Phase 09 实施计划

> 日期：2026-07-28 | 状态：已完成（2026-07-29）

- [x] 1. 设计规范和实施计划
- [x] 2. 后端 Audit 查询、分页和摘要回退
- [x] 3. 后端 Dashboard 聚合查询和统计
- [x] 4. 后端 Audit detail 增强
- [x] 5. 前端路由、导航、Dashboard 和 Audit 页面
- [x] 6. 更新文档（CLAUDE.md、architecture.md、database.md）
- [x] 7. 全量验证

## 详细任务

### 2. 后端 Audit 查询

- [x] 2.1 扩展 AuditLogMapper：分页查询、计数、最近活动
- [x] 2.2 创建 AuditLogItemView、AuditLogPageView
- [x] 2.3 创建 AuditSummaryFormatter
- [x] 2.4 创建 AuditLogQueryService
- [x] 2.5 创建 AuditLogController
- [x] 2.6 编写后端测试

### 3. 后端 Dashboard

- [x] 3.1 创建 ProjectDashboardMapper
- [x] 3.2 创建 DashboardView 及嵌套 View
- [x] 3.3 创建 ProjectDashboardQueryService
- [x] 3.4 创建 ProjectDashboardController
- [x] 3.5 编写后端测试

### 4. Audit detail 增强

- [x] 4.1 增强 AuditService.write() 接收 detail
- [x] 4.2 增强高价值事件写入 detail

### 5. 前端

- [x] 5.1 Dashboard 类型和 API
- [x] 5.2 Audit 类型和 API
- [x] 5.3 ProjectContextStore
- [x] 5.4 DashboardView.vue
- [x] 5.5 AuditLogView.vue
- [x] 5.6 更新 router.ts
- [x] 5.7 更新 AppShell.vue
- [x] 5.8 更新 ProjectListView.vue
- [x] 5.9 display-labels.ts 审计映射

### 6. 文档更新

- [x] 6.1 CLAUDE.md 状态更新
- [x] 6.2 architecture.md 更新
- [x] 6.3 database.md 更新（如需）

### 7. 全量验证

- [x] 7.1 后端测试
- [x] 7.2 后端构建
- [x] 7.3 前端 typecheck
- [x] 7.4 前端测试
- [x] 7.5 前端 build
- [x] 7.6 git diff --check
