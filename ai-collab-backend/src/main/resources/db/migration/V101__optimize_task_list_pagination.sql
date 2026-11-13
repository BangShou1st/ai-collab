-- H-4：任务分页的 status 过滤 + updated_at 排序组合。
-- project_id+assignee_id 与 project_id+milestone_id 已由 V1/V75 覆盖，不重复建索引。
CREATE INDEX IF NOT EXISTS ix_task_project_status_updated
ON project_task(project_id, status, updated_at DESC, id DESC);
