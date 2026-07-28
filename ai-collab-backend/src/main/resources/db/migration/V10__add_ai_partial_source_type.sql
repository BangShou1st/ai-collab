-- V10: Add AI_PARTIAL to version source_type check constraint
-- When generation degrades to READY_WITH_ISSUES, source must be AI_PARTIAL (not AI_COMPLETE)

ALTER TABLE ai_task_plan_version DROP CONSTRAINT IF EXISTS ck_ai_task_plan_version_source;

ALTER TABLE ai_task_plan_version ADD CONSTRAINT ck_ai_task_plan_version_source
    CHECK (source_type IN (
        'AI_SKELETON', 'AI_COMPLETE', 'AI_PARTIAL', 'AI_REPAIR', 'AI_PARTIAL_REPAIR',
        'AI_REGENERATED', 'MANUAL_EDIT', 'RESTORED'
    ));
