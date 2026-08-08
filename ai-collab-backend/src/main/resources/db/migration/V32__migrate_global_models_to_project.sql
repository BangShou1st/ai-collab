-- Phase 2: Migrate global model configs to demo project, then enforce NOT NULL
-- Target project: 8d741a93-7fde-449e-af3e-efd5f272a9ac
-- In test DBs where this project doesn't exist, orphan rows are deleted instead.

-- ============================================================
-- 1. Copy existing global model_configuration to demo project
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project WHERE id = '8d741a93-7fde-449e-af3e-efd5f272a9ac') THEN
        UPDATE model_configuration SET project_id = '8d741a93-7fde-449e-af3e-efd5f272a9ac' WHERE project_id IS NULL;
    ELSE
        DELETE FROM model_purpose_assignment WHERE project_id IS NULL;
        DELETE FROM model_configuration WHERE project_id IS NULL;
    END IF;
END $$;

-- ============================================================
-- 2. Copy existing global model_purpose_assignment to demo project
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project WHERE id = '8d741a93-7fde-449e-af3e-efd5f272a9ac') THEN
        UPDATE model_purpose_assignment SET project_id = '8d741a93-7fde-449e-af3e-efd5f272a9ac' WHERE project_id IS NULL;
    END IF;
END $$;

-- ============================================================
-- 3. Create embedding config for demo project (only if project exists)
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project WHERE id = '8d741a93-7fde-449e-af3e-efd5f272a9ac') THEN
        INSERT INTO project_embedding_config (
            project_id, provider, base_url, api_path, encrypted_api_key,
            model_name, dimensions, batch_size, enabled
        ) VALUES (
            '8d741a93-7fde-449e-af3e-efd5f272a9ac',
            'aliyun-bailian',
            'https://ws-v7ud2nl2x53nc8pv.cn-beijing.maas.aliyuncs.com/compatible-mode/v1',
            '/v1/embeddings',
            (SELECT encrypted_api_key FROM model_configuration WHERE project_id = '8d741a93-7fde-449e-af3e-efd5f272a9ac' LIMIT 1),
            'text-embedding-v4',
            1024,
            10,
            TRUE
        );
    END IF;
END $$;

-- ============================================================
-- 4. Copy existing MCP connections to demo project
-- ============================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM project WHERE id = '8d741a93-7fde-449e-af3e-efd5f272a9ac') THEN
        UPDATE agent_mcp_connection SET project_id = '8d741a93-7fde-449e-af3e-efd5f272a9ac' WHERE project_id IS NULL;
    ELSE
        DELETE FROM agent_mcp_connection WHERE project_id IS NULL;
    END IF;
END $$;

-- ============================================================
-- 5. Enforce NOT NULL after data migration
-- ============================================================
ALTER TABLE model_configuration ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE model_configuration ADD CONSTRAINT fk_model_configuration_project
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;

ALTER TABLE model_purpose_assignment ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE model_purpose_assignment ADD CONSTRAINT fk_model_purpose_assignment_project
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;

-- Drop old single-column PK, add composite PK (deferred from V31)
ALTER TABLE model_purpose_assignment DROP CONSTRAINT IF EXISTS model_purpose_assignment_pkey;
ALTER TABLE model_purpose_assignment ADD CONSTRAINT pk_model_purpose_assignment
    PRIMARY KEY (project_id, purpose);

ALTER TABLE agent_mcp_connection ALTER COLUMN project_id SET NOT NULL;
ALTER TABLE agent_mcp_connection ADD CONSTRAINT fk_agent_mcp_connection_project
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE;

-- ============================================================
-- 6. Add indexes for per-project queries
-- ============================================================
CREATE INDEX idx_model_configuration_project ON model_configuration(project_id, enabled);
CREATE INDEX idx_agent_mcp_connection_project ON agent_mcp_connection(project_id, enabled);
