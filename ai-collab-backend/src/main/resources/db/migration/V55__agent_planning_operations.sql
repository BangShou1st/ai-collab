CREATE TABLE agent_planning_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    invocation_id uuid NOT NULL UNIQUE REFERENCES agent_tool_invocation(invocation_id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    requester_id uuid NOT NULL REFERENCES app_user(id),
    session_id uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    origin_run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    goal_revision integer NOT NULL,
    kind varchar(30) NOT NULL,
    request_json jsonb NOT NULL,
    target_version_id uuid,
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    attempt_id uuid REFERENCES ai_task_plan_attempt(id),
    generation_seq bigint NOT NULL,
    status varchar(40) NOT NULL DEFAULT 'ACCEPTED',
    result_version_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX agent_planning_operation_session ON agent_planning_operation(project_id,session_id,created_at,id);
CREATE TABLE planning_model_snapshot (
    generation_id uuid PRIMARY KEY,
    requester_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    configuration_id uuid NOT NULL,
    configuration_updated_at timestamptz NOT NULL,
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE ai_task_plan_attempt ADD COLUMN repair_job_json jsonb;
ALTER TABLE ai_task_plan_attempt ADD COLUMN repair_previous_status varchar(30);
CREATE TABLE agent_planning_operation_event (
    id bigserial PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES agent_planning_operation(id) ON DELETE CASCADE,
    status varchar(40) NOT NULL,
    result_version_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
