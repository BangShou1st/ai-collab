-- Phase 3: Remove eval and schedule features (code and tables)

-- Drop knowledge eval tables
DROP TABLE IF EXISTS knowledge_eval_result;
DROP TABLE IF EXISTS knowledge_eval_run;

-- Drop agent schedule tables
DROP TABLE IF EXISTS agent_schedule_fire;
DROP TABLE IF EXISTS agent_schedule;

-- Remove AGENT_SCHEDULE_DISABLED from notification type constraint
ALTER TABLE notification DROP CONSTRAINT IF EXISTS ck_notification_type;
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'TASK_ASSIGNED', 'TASK_STATUS_CHANGED', 'TASK_DUE_SOON', 'TASK_OVERDUE',
    'DEPENDENCY_COMPLETED', 'DOCUMENT_PROCESSED', 'DOCUMENT_FAILED',
    'PLAN_CONFIRMED', 'COMMENT_ADDED', 'MILESTONE_COMPLETED',
    'AGENT_RUN_SUCCEEDED', 'AGENT_RUN_FAILED', 'AGENT_BUDGET_EXCEEDED',
    'AGENT_APPROVAL_REQUESTED'
));
