-- V7: Add validation issues and plan events tables for Phase 08 editable degradation

-- Validation issues: persisted per version, replaces flat error code list
CREATE TABLE ai_task_plan_validation_issue (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES ai_task_plan_version(id) ON DELETE CASCADE,
    code varchar(100) NOT NULL,
    severity varchar(40) NOT NULL,
    target_type varchar(40),
    target_temp_key varchar(160),
    field_name varchar(100),
    related_temp_key varchar(160),
    safe_details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    resolved boolean NOT NULL DEFAULT false,
    resolved_by uuid,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_issue_plan_version ON ai_task_plan_validation_issue(plan_id, version_id);
CREATE INDEX idx_issue_plan_resolved_severity ON ai_task_plan_validation_issue(plan_id, resolved, severity);

-- Plan events: audit trail for all plan mutations
CREATE TABLE ai_task_plan_event (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    from_version_id uuid REFERENCES ai_task_plan_version(id),
    to_version_id uuid REFERENCES ai_task_plan_version(id),
    actor_id uuid,
    event_type varchar(80) NOT NULL,
    changed_fields_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    before_hash varchar(64),
    after_hash varchar(64),
    issue_codes_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_plan_created ON ai_task_plan_event(plan_id, created_at);

-- Add READY_WITH_ISSUES to status check constraint if it exists
-- (PostgreSQL enum-like check: update the constraint to include new values)
DO $$
BEGIN
    -- Drop existing check constraint if present
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ai_task_plan_status_check') THEN
        ALTER TABLE ai_task_plan DROP CONSTRAINT ai_task_plan_status_check;
    END IF;
    -- Re-add with all status values including new ones
    ALTER TABLE ai_task_plan ADD CONSTRAINT ai_task_plan_status_check
        CHECK (status IN (
            'SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING',
            'DETAIL_GENERATION_FAILED', 'READY', 'READY_WITH_ISSUES',
            'CONFIRMING', 'CONFIRMED', 'FAILED', 'CANCELED'
        ));
END $$;
