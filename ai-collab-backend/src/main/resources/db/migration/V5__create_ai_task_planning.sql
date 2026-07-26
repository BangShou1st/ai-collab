-- The V1 planning tables were an unused placeholder and have no application code.
-- Phase 08 replaces them with the approved immutable-version model.
DROP TABLE IF EXISTS ai_task_plan_dependency;
DROP TABLE IF EXISTS ai_task_plan_task;
DROP TABLE IF EXISTS ai_task_plan_milestone;
DROP TABLE IF EXISTS ai_task_plan;

CREATE TABLE ai_task_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title varchar(160) NOT NULL,
    goal varchar(2000) NOT NULL,
    constraints varchar(4000) NOT NULL DEFAULT '',
    plan_start_date date NOT NULL,
    plan_due_date date NOT NULL,
    max_task_count integer NOT NULL,
    selected_document_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(40) NOT NULL DEFAULT 'SKELETON_GENERATING',
    latest_version_no integer NOT NULL DEFAULT 0,
    latest_version_id uuid,
    generation_seq bigint NOT NULL DEFAULT 1,
    active_attempt_id uuid,
    created_by uuid NOT NULL REFERENCES app_user(id),
    confirmed_by uuid REFERENCES app_user(id),
    confirmed_version_id uuid,
    confirmed_at timestamptz,
    canceled_at timestamptz,
    last_error_code varchar(80),
    last_error_summary varchar(500),
    lock_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_task_plan_status CHECK (status IN (
        'SKELETON_GENERATING', 'DETAIL_GENERATING', 'DETAIL_GENERATION_FAILED',
        'READY', 'CONFIRMING', 'CONFIRMED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_ai_task_plan_dates CHECK (plan_start_date <= plan_due_date),
    CONSTRAINT ck_ai_task_plan_max_tasks CHECK (max_task_count IN (10, 20, 30, 40)),
    CONSTRAINT ck_ai_task_plan_latest_version CHECK (latest_version_no >= 0),
    CONSTRAINT ck_ai_task_plan_selected_documents CHECK (
        jsonb_typeof(selected_document_ids_json) = 'array'
        AND jsonb_array_length(selected_document_ids_json) <= 10)
);
CREATE INDEX idx_ai_task_plan_project_updated
    ON ai_task_plan(project_id, updated_at DESC);
CREATE INDEX idx_ai_task_plan_project_status_updated
    ON ai_task_plan(project_id, status, updated_at DESC);
CREATE INDEX idx_ai_task_plan_creator_updated
    ON ai_task_plan(created_by, updated_at DESC);

CREATE TABLE ai_task_plan_version (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    version_no integer NOT NULL,
    source_type varchar(30) NOT NULL,
    based_on_version_id uuid REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT,
    generation_seq bigint NOT NULL,
    summary text NOT NULL DEFAULT '',
    assumptions_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    risks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    milestones_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    tasks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    sources_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    validation_result_json jsonb NOT NULL DEFAULT '{"errors":[],"warnings":[]}'::jsonb,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_task_plan_version_no UNIQUE(plan_id, version_no),
    CONSTRAINT ck_ai_task_plan_version_source CHECK (
        source_type IN ('AI_SKELETON', 'AI_COMPLETE', 'MANUAL_EDIT', 'RESTORED')),
    CONSTRAINT ck_ai_task_plan_version_no CHECK (version_no > 0)
);
CREATE INDEX idx_ai_task_plan_version_plan
    ON ai_task_plan_version(plan_id, version_no DESC);

ALTER TABLE ai_task_plan
    ADD CONSTRAINT fk_ai_task_plan_latest_version
        FOREIGN KEY (latest_version_id) REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_ai_task_plan_confirmed_version
        FOREIGN KEY (confirmed_version_id) REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT;

CREATE TABLE ai_task_plan_attempt (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    parent_attempt_id uuid REFERENCES ai_task_plan_attempt(id) ON DELETE CASCADE,
    attempt_no integer NOT NULL,
    generation_seq bigint NOT NULL,
    stage varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED',
    repair_count integer NOT NULL DEFAULT 0,
    cancel_requested boolean NOT NULL DEFAULT false,
    provider varchar(80),
    model varchar(120),
    started_at timestamptz,
    finished_at timestamptz,
    latency_ms integer,
    prompt_tokens integer,
    completion_tokens integer,
    error_code varchar(80),
    error_summary varchar(500),
    skeleton_version_id uuid REFERENCES ai_task_plan_version(id) ON DELETE SET NULL,
    result_version_id uuid REFERENCES ai_task_plan_version(id) ON DELETE SET NULL,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_task_plan_attempt_no UNIQUE(plan_id, attempt_no),
    CONSTRAINT ck_ai_task_plan_attempt_stage CHECK (stage IN ('SKELETON', 'DETAIL', 'REPAIR')),
    CONSTRAINT ck_ai_task_plan_attempt_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELED', 'DISCARDED')),
    CONSTRAINT ck_ai_task_plan_attempt_repair CHECK (repair_count BETWEEN 0 AND 1)
);
CREATE INDEX idx_ai_task_plan_attempt_plan
    ON ai_task_plan_attempt(plan_id, attempt_no DESC);
CREATE INDEX idx_ai_task_plan_attempt_plan_status
    ON ai_task_plan_attempt(plan_id, status);
CREATE INDEX idx_ai_task_plan_attempt_status_updated
    ON ai_task_plan_attempt(status, updated_at);

ALTER TABLE ai_task_plan
    ADD CONSTRAINT fk_ai_task_plan_active_attempt
        FOREIGN KEY (active_attempt_id) REFERENCES ai_task_plan_attempt(id) ON DELETE SET NULL;

CREATE TABLE ai_task_plan_confirmation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    version_id uuid NOT NULL REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'PROCESSING',
    created_milestone_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_task_ids_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_dependency_count integer NOT NULL DEFAULT 0,
    error_code varchar(80),
    error_summary varchar(500),
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_task_plan_confirmation_key UNIQUE(project_id, idempotency_key),
    CONSTRAINT uq_ai_task_plan_confirmation_plan UNIQUE(plan_id),
    CONSTRAINT ck_ai_task_plan_confirmation_status CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_ai_task_plan_confirmation_dependency_count CHECK (created_dependency_count >= 0)
);

ALTER TABLE milestone
    ADD COLUMN source_plan_id uuid REFERENCES ai_task_plan(id) ON DELETE RESTRICT,
    ADD COLUMN source_plan_version_id uuid REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT,
    ADD COLUMN source_plan_milestone_key varchar(80);
CREATE UNIQUE INDEX uq_milestone_source_plan_key
    ON milestone(source_plan_version_id, source_plan_milestone_key)
    WHERE source_plan_version_id IS NOT NULL;

ALTER TABLE project_task
    ADD COLUMN source_plan_id uuid REFERENCES ai_task_plan(id) ON DELETE RESTRICT,
    ADD COLUMN source_plan_version_id uuid REFERENCES ai_task_plan_version(id) ON DELETE RESTRICT,
    ADD COLUMN source_plan_task_key varchar(80);
CREATE UNIQUE INDEX uq_project_task_source_plan_key
    ON project_task(source_plan_version_id, source_plan_task_key)
    WHERE source_plan_version_id IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_confirmed_task_plan_delete()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'CONFIRMED' THEN
        RAISE EXCEPTION 'confirmed task plans cannot be deleted' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_ai_task_plan_prevent_confirmed_delete
    BEFORE DELETE ON ai_task_plan
    FOR EACH ROW EXECUTE FUNCTION prevent_confirmed_task_plan_delete();
