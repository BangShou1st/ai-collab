-- 崩溃接管计时锚点：最近一次由本运行 worker 确认落库的执行进度时间。
-- 租约有效期（claim_started_at + lease）不等于已执行时长；接管时只把
-- [claim_started_at, last_progress_at] 记为已确认执行时长，离线/排队/重试等待不计入。
-- 为空表示该 claim 未产生可确认的执行进度：尾段（最后一次进度到进程退出）无持久证据，
-- 不冒充已执行时长。
ALTER TABLE agent_run ADD COLUMN last_progress_at TIMESTAMPTZ;
