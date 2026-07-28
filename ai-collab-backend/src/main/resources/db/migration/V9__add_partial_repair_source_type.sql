-- V9: Add AI_PARTIAL_REPAIR and AI_REPAIR to version source_type check constraint
-- V5 only allowed AI_SKELETON, AI_COMPLETE, MANUAL_EDIT, RESTORED

ALTER TABLE ai_task_plan_version DROP CONSTRAINT IF EXISTS ck_ai_task_plan_version_source;

ALTER TABLE ai_task_plan_version ADD CONSTRAINT ck_ai_task_plan_version_source
    CHECK (source_type IN (
        'AI_SKELETON', 'AI_COMPLETE', 'AI_REPAIR', 'AI_PARTIAL_REPAIR',
        'AI_REGENERATED', 'MANUAL_EDIT', 'RESTORED'
    ));
