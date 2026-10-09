ALTER TABLE agent_run ADD COLUMN claim_version INTEGER;
ALTER TABLE agent_run ADD COLUMN model_configuration_id UUID;
ALTER TABLE agent_run ADD COLUMN model_configuration_updated_at TIMESTAMPTZ;
ALTER TABLE agent_session ADD COLUMN working_state JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE TABLE agent_tool_invocation (
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    turn_sequence INTEGER NOT NULL,
    ordinal INTEGER NOT NULL,
    invocation_id UUID NOT NULL UNIQUE,
    tool_call_id VARCHAR(300) NOT NULL,
    tool_name VARCHAR(300) NOT NULL,
    arguments_json JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('PENDING','SUCCEEDED','FAILED','REJECTED','CANCELED','SKIPPED','UNKNOWN')),
    result_json JSONB,
    proposal_id UUID REFERENCES agent_approval(id),
    proposal_operation VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(run_id,turn_sequence,ordinal)
);
