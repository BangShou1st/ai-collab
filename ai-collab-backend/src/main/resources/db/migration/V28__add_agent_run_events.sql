ALTER TABLE agent_run
    ADD COLUMN cancel_requested_at timestamptz,
    ADD COLUMN last_event_sequence bigint NOT NULL DEFAULT 0;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_last_event_sequence CHECK (last_event_sequence >= 0);

CREATE TABLE agent_run_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    sequence_no bigint NOT NULL,
    type varchar(48) NOT NULL,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_run_event_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT ck_agent_run_event_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_agent_run_event_type CHECK (type IN (
        'RUN_CREATED','CONTEXT_CAPTURED','SKILL_SELECTED',
        'PLAN_CREATED','PLAN_UPDATED','MODEL_STARTED','MODEL_COMPLETED',
        'TOOL_CALL_PROPOSED','TOOL_CALL_STARTED','TOOL_CALL_COMPLETED','TOOL_CALL_FAILED',
        'APPROVAL_REQUESTED','APPROVAL_APPROVED','APPROVAL_REJECTED',
        'APPROVAL_EXPIRED','RESULT_VERIFIED','RUN_RETRY_SCHEDULED',
        'RUN_CANCELED','RUN_SUCCEEDED','RUN_FAILED','RUN_BUDGET_EXCEEDED'
    ))
);

CREATE INDEX idx_agent_run_event_replay
    ON agent_run_event(project_id, run_id, sequence_no);
