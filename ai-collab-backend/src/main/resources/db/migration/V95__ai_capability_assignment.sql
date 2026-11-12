-- V95: 能力用途绑定（GLOBAL/PROJECT），RERANK 使用独立 purpose KNOWLEDGE_RERANK。

CREATE TABLE ai_capability_assignment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capability VARCHAR(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    project_id UUID REFERENCES project(id) ON DELETE CASCADE,
    model_capability_profile_id UUID NOT NULL REFERENCES ai_model_capability_profile(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_capability_assignment_scope CHECK (scope IN ('GLOBAL', 'PROJECT')),
    CONSTRAINT ck_ai_capability_assignment_capability CHECK (
        capability IN ('CHAT', 'EMBEDDING', 'RERANK')
    ),
    CONSTRAINT ck_ai_capability_assignment_purpose CHECK (
        purpose IN ('KNOWLEDGE_CHAT', 'AGENT', 'PLANNING', 'KNOWLEDGE_RERANK')
    ),
    CONSTRAINT ck_ai_capability_assignment_rerank_purpose CHECK (
        capability <> 'RERANK' OR purpose = 'KNOWLEDGE_RERANK'
    ),
    CONSTRAINT ck_ai_capability_assignment_scope_project CHECK (
        (scope = 'GLOBAL' AND project_id IS NULL)
        OR (scope = 'PROJECT' AND project_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_ai_capability_assignment_global
    ON ai_capability_assignment (capability, purpose)
    WHERE scope = 'GLOBAL' AND project_id IS NULL;

CREATE UNIQUE INDEX uk_ai_capability_assignment_project
    ON ai_capability_assignment (capability, purpose, project_id)
    WHERE scope = 'PROJECT' AND project_id IS NOT NULL;

CREATE INDEX idx_ai_capability_assignment_runtime
    ON ai_capability_assignment (capability, purpose, scope, project_id, enabled);

COMMENT ON TABLE ai_capability_assignment IS '能力用途绑定；RERANK 只使用 KNOWLEDGE_RERANK，不复用 KNOWLEDGE_CHAT';
