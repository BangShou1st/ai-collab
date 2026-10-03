ALTER TABLE ai_task_plan_attempt
    ADD COLUMN diagnostics_json JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE ai_task_plan_attempt
    ADD CONSTRAINT chk_plan_attempt_diagnostics_array CHECK (jsonb_typeof(diagnostics_json) = 'array');
