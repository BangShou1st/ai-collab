CREATE TABLE agent_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    creator_id uuid NOT NULL REFERENCES app_user(id),
    title varchar(160) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_session_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_agent_session_version CHECK (version >= 0)
);
CREATE INDEX idx_agent_session_project
    ON agent_session(project_id, creator_id, updated_at DESC);

CREATE TABLE agent_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    requester_id uuid NOT NULL REFERENCES app_user(id),
    parent_run_id uuid REFERENCES agent_run(id) ON DELETE CASCADE,
    role varchar(40) NOT NULL DEFAULT 'SUPERVISOR',
    depth smallint NOT NULL DEFAULT 0,
    goal varchar(4000) NOT NULL,
    model_provider varchar(80),
    model_name varchar(120),
    status varchar(32) NOT NULL DEFAULT 'CREATED',
    max_steps integer NOT NULL DEFAULT 12,
    max_tool_calls integer NOT NULL DEFAULT 8,
    max_children integer NOT NULL DEFAULT 3,
    max_input_tokens integer NOT NULL DEFAULT 50000,
    max_output_tokens integer NOT NULL DEFAULT 20000,
    steps_used integer NOT NULL DEFAULT 0,
    tool_calls_used integer NOT NULL DEFAULT 0,
    children_used integer NOT NULL DEFAULT 0,
    input_tokens_used integer NOT NULL DEFAULT 0,
    output_tokens_used integer NOT NULL DEFAULT 0,
    token_usage_estimated boolean NOT NULL DEFAULT false,
    estimated_cost numeric(14,6) NOT NULL DEFAULT 0,
    scheduled boolean NOT NULL DEFAULT false,
    correction_attempted boolean NOT NULL DEFAULT false,
    retry_count integer NOT NULL DEFAULT 0,
    retry_after timestamptz,
    lease_owner varchar(120),
    lease_expires_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    error_code varchar(80),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_run_status CHECK (status IN (
        'CREATED', 'QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'SUCCEEDED',
        'FAILED_RETRYABLE', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED'
    )),
    CONSTRAINT ck_agent_run_role CHECK (role IN (
        'SUPERVISOR', 'KNOWLEDGE_RESEARCHER', 'PROGRESS_ANALYST', 'RISK_REVIEWER'
    )),
    CONSTRAINT ck_agent_run_depth CHECK (
        (parent_run_id IS NULL AND depth = 0 AND role = 'SUPERVISOR')
        OR (parent_run_id IS NOT NULL AND depth = 1 AND role <> 'SUPERVISOR')
    ),
    CONSTRAINT ck_agent_run_budgets CHECK (
        max_steps BETWEEN 1 AND 100
        AND max_tool_calls BETWEEN 0 AND 100
        AND max_children BETWEEN 0 AND 3
        AND max_input_tokens BETWEEN 1 AND 2000000
        AND max_output_tokens BETWEEN 1 AND 500000
        AND steps_used BETWEEN 0 AND max_steps
        AND tool_calls_used BETWEEN 0 AND max_tool_calls
        AND children_used BETWEEN 0 AND max_children
        AND input_tokens_used BETWEEN 0 AND max_input_tokens
        AND output_tokens_used BETWEEN 0 AND max_output_tokens
        AND estimated_cost >= 0
        AND retry_count BETWEEN 0 AND 10
        AND version >= 0
    )
);
CREATE INDEX idx_agent_run_claim
    ON agent_run(status, retry_after, lease_expires_at, created_at);
CREATE INDEX idx_agent_run_project
    ON agent_run(project_id, session_id, created_at DESC);
CREATE INDEX idx_agent_run_parent ON agent_run(parent_run_id);

CREATE TABLE agent_step (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    sequence_no integer NOT NULL,
    type varchar(40) NOT NULL,
    tool_name varchar(120),
    input_json jsonb,
    output_json jsonb,
    reason varchar(2000),
    prompt_tokens integer,
    completion_tokens integer,
    token_usage_estimated boolean NOT NULL DEFAULT false,
    latency_ms integer,
    error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_step_sequence UNIQUE (run_id, sequence_no),
    CONSTRAINT ck_agent_step_type CHECK (type IN (
        'MODEL_REQUEST', 'MODEL_DECISION', 'TOOL_CALL_PROPOSED',
        'TOOL_CALL_COMPLETED', 'APPROVAL_REQUESTED', 'APPROVAL_RESOLVED',
        'DELEGATION_REQUESTED', 'DELEGATION_COMPLETED', 'FINAL_ANSWER', 'ERROR'
    )),
    CONSTRAINT ck_agent_step_metrics CHECK (
        sequence_no > 0
        AND (prompt_tokens IS NULL OR prompt_tokens >= 0)
        AND (completion_tokens IS NULL OR completion_tokens >= 0)
        AND (latency_ms IS NULL OR latency_ms >= 0)
    )
);
CREATE INDEX idx_agent_step_run ON agent_step(run_id, sequence_no);

CREATE TABLE agent_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    run_id uuid REFERENCES agent_run(id) ON DELETE SET NULL,
    role varchar(20) NOT NULL,
    content varchar(12000) NOT NULL,
    citations_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    inferences_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
);
CREATE INDEX idx_agent_message_session
    ON agent_message(session_id, created_at, id);

CREATE TABLE agent_approval (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    step_id uuid NOT NULL REFERENCES agent_step(id) ON DELETE CASCADE,
    tool_name varchar(120) NOT NULL,
    arguments_json jsonb NOT NULL,
    arguments_hash char(64) NOT NULL,
    diff_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    resource_id uuid,
    resource_version integer,
    status varchar(24) NOT NULL DEFAULT 'PENDING',
    requester_id uuid NOT NULL REFERENCES app_user(id),
    approver_id uuid REFERENCES app_user(id),
    nonce_hash char(64) NOT NULL,
    idempotency_key uuid,
    result_json jsonb,
    rejection_reason varchar(500),
    expires_at timestamptz NOT NULL,
    resolved_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_approval_step UNIQUE (step_id),
    CONSTRAINT uq_agent_approval_idempotency UNIQUE (project_id, idempotency_key),
    CONSTRAINT ck_agent_approval_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CONFLICTED')
    ),
    CONSTRAINT ck_agent_approval_version CHECK (
        version >= 0 AND (resource_version IS NULL OR resource_version >= 0)
    )
);
CREATE INDEX idx_agent_approval_pending
    ON agent_approval(project_id, status, expires_at, created_at DESC);

CREATE TABLE agent_schedule (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    creator_id uuid NOT NULL REFERENCES app_user(id),
    session_id uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    name varchar(120) NOT NULL,
    goal varchar(4000) NOT NULL,
    frequency varchar(16) NOT NULL,
    time_zone varchar(80) NOT NULL DEFAULT 'Asia/Shanghai',
    local_time time NOT NULL,
    weekly_day smallint,
    enabled boolean NOT NULL DEFAULT true,
    next_fire_at timestamptz NOT NULL,
    last_run_id uuid REFERENCES agent_run(id) ON DELETE SET NULL,
    last_status varchar(32),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_schedule_frequency CHECK (
        (frequency = 'DAILY' AND weekly_day IS NULL)
        OR (frequency = 'WEEKLY' AND weekly_day BETWEEN 1 AND 7)
    ),
    CONSTRAINT ck_agent_schedule_version CHECK (version >= 0)
);
CREATE INDEX idx_agent_schedule_due
    ON agent_schedule(enabled, next_fire_at);
CREATE INDEX idx_agent_schedule_project
    ON agent_schedule(project_id, created_at DESC);

CREATE TABLE agent_schedule_fire (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id uuid NOT NULL REFERENCES agent_schedule(id) ON DELETE CASCADE,
    scheduled_for timestamptz NOT NULL,
    run_id uuid NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_schedule_fire UNIQUE (schedule_id, scheduled_for)
);

CREATE TABLE agent_evaluation_template (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(80) NOT NULL,
    name varchar(160) NOT NULL,
    category varchar(40) NOT NULL,
    version integer NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_evaluation_template UNIQUE (code, version),
    CONSTRAINT ck_agent_evaluation_template_version CHECK (version > 0)
);

CREATE TABLE agent_evaluation_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id uuid NOT NULL REFERENCES agent_evaluation_template(id) ON DELETE CASCADE,
    name varchar(160) NOT NULL,
    input_json jsonb NOT NULL,
    allowed_tools_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    forbidden_tools_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    expected_status varchar(32) NOT NULL,
    assertions_json jsonb NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_agent_evaluation_case_sort CHECK (sort_order >= 0)
);

CREATE TABLE agent_evaluation_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    template_id uuid NOT NULL REFERENCES agent_evaluation_template(id),
    requested_by uuid NOT NULL REFERENCES app_user(id),
    mode varchar(20) NOT NULL DEFAULT 'FIXTURE',
    status varchar(24) NOT NULL DEFAULT 'QUEUED',
    model_provider varchar(80),
    model_name varchar(120),
    total_cases integer NOT NULL DEFAULT 0,
    passed_cases integer NOT NULL DEFAULT 0,
    tool_choice_accuracy numeric(6,5) NOT NULL DEFAULT 0,
    citation_coverage numeric(6,5) NOT NULL DEFAULT 0,
    budget_compliance numeric(6,5) NOT NULL DEFAULT 0,
    approval_bypass_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CONSTRAINT ck_agent_evaluation_run_mode CHECK (mode IN ('FIXTURE', 'REAL_MODEL')),
    CONSTRAINT ck_agent_evaluation_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    CONSTRAINT ck_agent_evaluation_run_metrics CHECK (
        total_cases >= 0 AND passed_cases BETWEEN 0 AND total_cases
        AND tool_choice_accuracy BETWEEN 0 AND 1
        AND citation_coverage BETWEEN 0 AND 1
        AND budget_compliance BETWEEN 0 AND 1
        AND approval_bypass_count >= 0
    )
);

CREATE TABLE agent_evaluation_result (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_run_id uuid NOT NULL REFERENCES agent_evaluation_run(id) ON DELETE CASCADE,
    case_id uuid NOT NULL REFERENCES agent_evaluation_case(id),
    passed boolean NOT NULL,
    actual_status varchar(32),
    actual_tools_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    citations_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    steps_used integer NOT NULL DEFAULT 0,
    tool_calls_used integer NOT NULL DEFAULT 0,
    input_tokens integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    approval_bypass_count integer NOT NULL DEFAULT 0,
    failure_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_evaluation_result UNIQUE (evaluation_run_id, case_id),
    CONSTRAINT ck_agent_evaluation_result_metrics CHECK (
        steps_used >= 0 AND tool_calls_used >= 0
        AND input_tokens >= 0 AND output_tokens >= 0
        AND approval_bypass_count >= 0
    )
);

INSERT INTO agent_evaluation_template(code, name, category, version) VALUES
    ('SOURCED_QA', '项目问答引用正确性', 'QUALITY', 1),
    ('PROGRESS_FACTS', '进度事实一致性', 'QUALITY', 1),
    ('SAFE_TOOL_SELECTION', '安全工具选择', 'SAFETY', 1),
    ('BUDGET_TERMINATION', '预算终止', 'RELIABILITY', 1),
    ('APPROVAL_BYPASS', '审批绕过防护', 'SAFETY', 1);

ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'TASK_ASSIGNED', 'TASK_STATUS_CHANGED', 'TASK_DUE_SOON', 'TASK_OVERDUE',
    'DEPENDENCY_COMPLETED', 'DOCUMENT_PROCESSED', 'DOCUMENT_FAILED',
    'PLAN_CONFIRMED', 'COMMENT_ADDED', 'MILESTONE_COMPLETED',
    'AGENT_RUN_SUCCEEDED', 'AGENT_RUN_FAILED', 'AGENT_BUDGET_EXCEEDED',
    'AGENT_APPROVAL_REQUESTED', 'AGENT_SCHEDULE_DISABLED'
));
