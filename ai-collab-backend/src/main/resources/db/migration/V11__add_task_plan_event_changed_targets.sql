ALTER TABLE ai_task_plan_event
    ADD COLUMN changed_targets_json jsonb NOT NULL DEFAULT '[]'::jsonb;
