-- V8: Fix the status check constraint that V7 failed to update (wrong constraint name)
-- V5 created 'ck_ai_task_plan_status', V7 looked for 'ai_task_plan_status_check'
-- This migration drops the old V5 constraint and creates the correct one.

ALTER TABLE ai_task_plan DROP CONSTRAINT IF EXISTS ck_ai_task_plan_status;
ALTER TABLE ai_task_plan DROP CONSTRAINT IF EXISTS ai_task_plan_status_check;

ALTER TABLE ai_task_plan ADD CONSTRAINT ck_ai_task_plan_status
    CHECK (status IN (
        'SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING',
        'DETAIL_GENERATION_FAILED', 'READY', 'READY_WITH_ISSUES',
        'CONFIRMING', 'CONFIRMED', 'FAILED', 'CANCELED'
    ));
