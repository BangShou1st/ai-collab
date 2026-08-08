-- Phase 1: Per-project model configuration, embedding config, and MCP refactor
-- All AI model/embedding/MCP config becomes project-scoped

-- ============================================================
-- 1. model_configuration: add project_id, adjust unique constraint
-- ============================================================
ALTER TABLE model_configuration ADD COLUMN project_id UUID;

-- Migrate existing rows to demo project (will be set non-null after V32 data migration)
-- Update unique constraint: name is now unique per project, not globally
ALTER TABLE model_configuration DROP CONSTRAINT IF EXISTS uk_model_configuration_name;
ALTER TABLE model_configuration ADD CONSTRAINT uk_model_configuration_project_name
    UNIQUE (project_id, name);

-- ============================================================
-- 2. model_purpose_assignment: add project_id
-- PK change deferred to V32 after data migration populates project_id
-- ============================================================
ALTER TABLE model_purpose_assignment ADD COLUMN project_id UUID;

-- ============================================================
-- 3. project_embedding_config: new table for per-project embedding
-- ============================================================
CREATE TABLE project_embedding_config (
    project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL DEFAULT 'openai-compatible',
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL DEFAULT '/v1/embeddings',
    encrypted_api_key TEXT,
    model_name VARCHAR(160) NOT NULL,
    dimensions INTEGER NOT NULL DEFAULT 1024,
    batch_size INTEGER NOT NULL DEFAULT 10,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_embedding_dimensions CHECK (dimensions BETWEEN 1 AND 4096),
    CONSTRAINT ck_embedding_batch_size CHECK (batch_size BETWEEN 1 AND 256)
);

-- ============================================================
-- 4. agent_mcp_connection: add project_id, adjust unique constraint
-- ============================================================
ALTER TABLE agent_mcp_connection ADD COLUMN project_id UUID;

-- code is now unique per project, not globally
ALTER TABLE agent_mcp_connection DROP CONSTRAINT IF EXISTS agent_mcp_connection_code_key;
ALTER TABLE agent_mcp_connection ADD CONSTRAINT uk_agent_mcp_connection_project_code
    UNIQUE (project_id, code);

-- ============================================================
-- 5. Drop agent_project_mcp_binding (redundant with per-project connections)
-- ============================================================
DROP TABLE IF EXISTS agent_project_mcp_binding;
