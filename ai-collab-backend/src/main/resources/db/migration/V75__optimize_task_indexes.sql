-- V75: PostgreSQL 热点 SQL 优化索引
-- 覆盖 HomeMapper.myTasks, TaskMapper.listScoped, ProjectDashboardMapper 热点高频查询

-- 1. 首页我的待办任务 (HomeMapper.myTasks)
-- 场景: 过滤当前用户的进行中任务 (TODO/IN_PROGRESS/BLOCKED)，按 due_date ASC NULLS LAST, updated_at DESC 排序
-- 优化原理: 过滤条件包含 assignee_id 与 status 范围，通过部分索引排除海量已完成(DONE/CANCELED)任务；
--           索引列物理有序排列，消除 Sort 节点 (Top-N Heapsort 变为 0 排序)，直接流式返回前 N 条。
CREATE INDEX IF NOT EXISTS idx_task_assignee_active_due
ON project_task(assignee_id, due_date ASC NULLS LAST, updated_at DESC)
WHERE status IN ('TODO', 'IN_PROGRESS', 'BLOCKED');

-- 2. 项目任务列表与看板全量加载 (TaskMapper.listScoped)
-- 场景: 项目任务按 created_at 升序展示
-- 优化原理: project_id 等值过滤配合 created_at ASC 排序，消除大项目下的 External Merge / QuickSort。
CREATE INDEX IF NOT EXISTS idx_task_project_created
ON project_task(project_id, created_at ASC);

-- 3. 项目仪表盘最近更新任务 (ProjectDashboardMapper.listRecentTasks)
-- 场景: 项目最近活跃任务 Top 5 (ORDER BY updated_at DESC, id DESC LIMIT 5)
-- 优化原理: 索引物理顺序精确匹配更新时间与自增/主键排序，实现 Early Stop 提前终止，扫描行数由全表变为固定 5 行。
CREATE INDEX IF NOT EXISTS idx_task_project_updated
ON project_task(project_id, updated_at DESC, id DESC);

-- 4. 仪表盘里程碑任务进度统计 (ProjectDashboardMapper.listMilestoneProgress)
-- 场景: 按里程碑分组统计任务完成率 (WHERE project_id = ? AND milestone_id IS NOT NULL GROUP BY milestone_id)
-- 优化原理: 复合索引覆盖 project_id + milestone_id，结合部分索引排除无里程碑任务，加速分组聚合。
CREATE INDEX IF NOT EXISTS idx_task_project_milestone
ON project_task(project_id, milestone_id)
WHERE milestone_id IS NOT NULL;

-- 5. 任务反向依赖图与级联查询 (TaskMapper.findDependents)
-- 场景: 查找依赖于指定任务的后置任务 (WHERE depends_on_task_id = ?)
-- 优化原理: 原主键为 (task_id, depends_on_task_id)，前缀为 task_id 导致反向查询全表扫描；补充该单列索引支持双向毫秒级遍历。
CREATE INDEX IF NOT EXISTS idx_task_dependency_depends
ON task_dependency(depends_on_task_id);

-- 6. 项目仪表盘最近文档 (ProjectDashboardMapper.listRecentDocuments)
-- 场景: 项目最近上传文档 Top 5 (ORDER BY created_at DESC, id DESC LIMIT 5)
-- 优化原理: project_id 等值过滤配合 created_at DESC, id DESC，消除排序节点，实现 Index Scan Top-N 提前终止。
CREATE INDEX IF NOT EXISTS idx_document_project_recent
ON project_document(project_id, created_at DESC, id DESC);
