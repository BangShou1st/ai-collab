CREATE TABLE agent_memory (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    type varchar(24) NOT NULL,
    title varchar(160) NOT NULL,
    content varchar(2000) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id uuid,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_by uuid NOT NULL REFERENCES app_user(id),
    updated_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_memory_type CHECK (type IN ('DECISION','PREFERENCE','CONSTRAINT','LESSON')),
    CONSTRAINT ck_agent_memory_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT ck_agent_memory_version CHECK (version >= 0)
);
CREATE INDEX idx_agent_memory_project_active ON agent_memory(project_id,status,updated_at DESC);

ALTER TABLE agent_schedule ADD COLUMN skill_code varchar(64);
