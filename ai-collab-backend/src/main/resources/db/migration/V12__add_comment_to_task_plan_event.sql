-- V12: Add comment column to task_plan_event for user-provided change descriptions

ALTER TABLE ai_task_plan_event ADD COLUMN comment varchar(500);
