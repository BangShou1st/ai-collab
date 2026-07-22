CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(40) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    display_name varchar(60) NOT NULL,
    email varchar(120) UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    token_version integer NOT NULL DEFAULT 0,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE refresh_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_token_user ON refresh_token(user_id, expires_at);

CREATE TABLE project (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(100) NOT NULL,
    description varchar(2000) NOT NULL DEFAULT '',
    owner_id uuid NOT NULL REFERENCES app_user(id),
    start_date date,
    due_date date,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_project_dates CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date)
);
CREATE INDEX idx_project_owner ON project(owner_id);

CREATE TABLE project_member (
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role varchar(20) NOT NULL,
    invited_by uuid REFERENCES app_user(id),
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT ck_project_member_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);
CREATE INDEX idx_project_member_user ON project_member(user_id, project_id);

CREATE TABLE project_invitation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    invite_code_hash char(64) NOT NULL UNIQUE,
    invited_email varchar(120),
    role varchar(20) NOT NULL DEFAULT 'MEMBER',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    accepted_by uuid REFERENCES app_user(id),
    accepted_at timestamptz,
    created_by uuid NOT NULL REFERENCES app_user(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_project_invitation_role CHECK (role IN ('ADMIN', 'MEMBER')),
    CONSTRAINT ck_project_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
);
CREATE INDEX idx_project_invitation_project ON project_invitation(project_id, status);

CREATE TABLE milestone (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name varchar(100) NOT NULL,
    description varchar(1000) NOT NULL DEFAULT '',
    target_date date,
    status varchar(20) NOT NULL DEFAULT 'PLANNED',
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_milestone_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELED'))
);
CREATE INDEX idx_milestone_project ON milestone(project_id, sort_order);

CREATE TABLE project_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    milestone_id uuid REFERENCES milestone(id) ON DELETE SET NULL,
    title varchar(160) NOT NULL,
    description varchar(4000) NOT NULL DEFAULT '',
    status varchar(20) NOT NULL DEFAULT 'TODO',
    priority varchar(20) NOT NULL DEFAULT 'MEDIUM',
    assignee_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
    estimate_hours numeric(6,2),
    start_date date,
    due_date date,
    completed_at timestamptz,
    sort_order integer NOT NULL DEFAULT 0,
    created_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELED')),
    CONSTRAINT ck_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_task_dates CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date),
    CONSTRAINT ck_task_estimate CHECK (estimate_hours IS NULL OR (estimate_hours >= 0.5 AND estimate_hours <= 80))
);
CREATE INDEX idx_task_project_status ON project_task(project_id, status);
CREATE INDEX idx_task_project_assignee ON project_task(project_id, assignee_id);
CREATE INDEX idx_task_project_due ON project_task(project_id, due_date);
CREATE INDEX idx_task_milestone ON project_task(milestone_id);

CREATE TABLE task_dependency (
    task_id uuid NOT NULL REFERENCES project_task(id) ON DELETE CASCADE,
    depends_on_task_id uuid NOT NULL REFERENCES project_task(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, depends_on_task_id),
    CONSTRAINT ck_task_dependency_not_self CHECK (task_id <> depends_on_task_id)
);

CREATE TABLE task_comment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    task_id uuid NOT NULL REFERENCES project_task(id) ON DELETE CASCADE,
    author_id uuid NOT NULL REFERENCES app_user(id),
    content varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_comment_task ON task_comment(task_id, created_at);

CREATE TABLE project_document (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    display_name varchar(180) NOT NULL,
    original_filename varchar(180) NOT NULL,
    mime_type varchar(120) NOT NULL,
    size_bytes bigint NOT NULL,
    object_key varchar(500) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'UPLOADED',
    parser_type varchar(40),
    chunk_count integer NOT NULL DEFAULT 0,
    embedding_provider varchar(80),
    embedding_model varchar(120),
    embedding_dimension integer,
    error_message varchar(1000),
    uploaded_by uuid NOT NULL REFERENCES app_user(id),
    indexed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_status CHECK (status IN ('UPLOADED', 'PARSING', 'INDEXING', 'READY', 'FAILED', 'DELETING')),
    CONSTRAINT ck_document_size CHECK (size_bytes > 0 AND size_bytes <= 20971520)
);
CREATE INDEX idx_document_project_status ON project_document(project_id, status);

CREATE TABLE document_chunk (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    document_id uuid NOT NULL REFERENCES project_document(id) ON DELETE CASCADE,
    chunk_no integer NOT NULL,
    heading varchar(300),
    content text NOT NULL,
    content_hash char(64) NOT NULL,
    token_estimate integer NOT NULL DEFAULT 0,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding_provider varchar(80) NOT NULL,
    embedding_model varchar(120) NOT NULL,
    embedding_dimension integer NOT NULL,
    embedding vector NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(document_id, chunk_no)
);
CREATE INDEX idx_document_chunk_project_document ON document_chunk(project_id, document_id);
CREATE INDEX idx_document_chunk_hash ON document_chunk(project_id, content_hash);

CREATE TABLE knowledge_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title varchar(120) NOT NULL DEFAULT 'New Session',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_session_user ON knowledge_session(project_id, user_id, updated_at DESC);

CREATE TABLE knowledge_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL REFERENCES knowledge_session(id) ON DELETE CASCADE,
    role varchar(20) NOT NULL,
    content text NOT NULL,
    insufficient_evidence boolean NOT NULL DEFAULT false,
    model_provider varchar(80),
    model_name varchar(120),
    latency_ms integer,
    prompt_tokens integer,
    completion_tokens integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_knowledge_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);
CREATE INDEX idx_knowledge_message_session ON knowledge_message(session_id, created_at);

CREATE TABLE knowledge_citation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES knowledge_message(id) ON DELETE CASCADE,
    chunk_id uuid NOT NULL REFERENCES document_chunk(id) ON DELETE CASCADE,
    rank integer NOT NULL,
    similarity numeric(6,5) NOT NULL,
    quote_text varchar(1000) NOT NULL,
    UNIQUE(message_id, rank)
);

CREATE TABLE ai_task_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    created_by uuid NOT NULL REFERENCES app_user(id),
    goal varchar(2000) NOT NULL,
    start_date date NOT NULL,
    due_date date NOT NULL,
    max_tasks integer NOT NULL,
    constraints_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    selected_document_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'GENERATING',
    model_provider varchar(80),
    model_name varchar(120),
    raw_response jsonb,
    summary text,
    assumptions_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    risks_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    error_message varchar(1000),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_task_plan_status CHECK (status IN ('GENERATING', 'READY', 'CONFIRMED', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_task_plan_dates CHECK (start_date <= due_date),
    CONSTRAINT ck_task_plan_max_tasks CHECK (max_tasks BETWEEN 5 AND 50)
);
CREATE INDEX idx_task_plan_project ON ai_task_plan(project_id, created_at DESC);

CREATE TABLE ai_task_plan_milestone (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    temp_key varchar(20) NOT NULL,
    name varchar(100) NOT NULL,
    description varchar(1000) NOT NULL DEFAULT '',
    target_date date,
    sort_order integer NOT NULL DEFAULT 0,
    UNIQUE(plan_id, temp_key)
);

CREATE TABLE ai_task_plan_task (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    temp_key varchar(20) NOT NULL,
    milestone_temp_key varchar(20) NOT NULL,
    title varchar(160) NOT NULL,
    description varchar(4000) NOT NULL DEFAULT '',
    priority varchar(20) NOT NULL,
    estimate_hours numeric(6,2),
    start_date date,
    due_date date,
    suggested_assignee_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
    source_document_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    sort_order integer NOT NULL DEFAULT 0,
    UNIQUE(plan_id, temp_key),
    CONSTRAINT ck_plan_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT ck_plan_task_dates CHECK (start_date IS NULL OR due_date IS NULL OR start_date <= due_date),
    CONSTRAINT ck_plan_task_estimate CHECK (estimate_hours IS NULL OR (estimate_hours >= 0.5 AND estimate_hours <= 80))
);

CREATE TABLE ai_task_plan_dependency (
    plan_id uuid NOT NULL REFERENCES ai_task_plan(id) ON DELETE CASCADE,
    task_temp_key varchar(20) NOT NULL,
    depends_on_temp_key varchar(20) NOT NULL,
    PRIMARY KEY (plan_id, task_temp_key, depends_on_temp_key),
    CONSTRAINT ck_plan_dependency_not_self CHECK (task_temp_key <> depends_on_temp_key)
);

CREATE TABLE idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    endpoint varchar(200) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    response_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE(user_id, endpoint, idempotency_key)
);

CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid REFERENCES project(id) ON DELETE CASCADE,
    user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
    action varchar(80) NOT NULL,
    entity_type varchar(80) NOT NULL,
    entity_id uuid,
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_project_time ON audit_log(project_id, created_at DESC);

CREATE TABLE ai_call_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
    project_id uuid REFERENCES project(id) ON DELETE SET NULL,
    feature varchar(40) NOT NULL,
    provider varchar(80) NOT NULL,
    model varchar(120) NOT NULL,
    status varchar(30) NOT NULL,
    latency_ms integer,
    prompt_tokens integer,
    completion_tokens integer,
    error_code varchar(80),
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_call_status CHECK (status IN ('SUCCESS', 'TIMEOUT', 'QUOTA_EXCEEDED', 'PROVIDER_ERROR', 'INVALID_OUTPUT'))
);
CREATE INDEX idx_ai_call_project_time ON ai_call_log(project_id, created_at DESC);
