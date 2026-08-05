CREATE TABLE agent_mcp_connection (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(60) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    transport varchar(32) NOT NULL,
    endpoint varchar(1000),
    stdio_command_json jsonb,
    auth_type varchar(32) NOT NULL DEFAULT 'NONE',
    credential_ciphertext text,
    credential_key_version integer,
    timeout_ms integer NOT NULL DEFAULT 15000,
    max_result_bytes integer NOT NULL DEFAULT 32768,
    tool_allowlist_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    resource_allowlist_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    discovered_tools_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    discovered_resources_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    schema_hash char(64),
    confirmed_schema_hash char(64),
    enabled boolean NOT NULL DEFAULT false,
    last_health_status varchar(24),
    last_health_message varchar(500),
    last_health_at timestamptz,
    created_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_mcp_transport CHECK (transport IN ('STDIO','SSE','STREAMABLE_HTTP')),
    CONSTRAINT ck_agent_mcp_auth CHECK (auth_type IN ('NONE','BEARER','OAUTH21')),
    CONSTRAINT ck_agent_mcp_limits CHECK (timeout_ms BETWEEN 1000 AND 60000 AND max_result_bytes BETWEEN 1024 AND 262144 AND version >= 0),
    CONSTRAINT ck_agent_mcp_transport_config CHECK (
      (transport IN ('SSE','STREAMABLE_HTTP') AND endpoint IS NOT NULL AND stdio_command_json IS NULL)
      OR (transport='STDIO' AND endpoint IS NULL AND stdio_command_json IS NOT NULL)
    )
);

CREATE TABLE agent_project_mcp_binding (
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    connection_id uuid NOT NULL REFERENCES agent_mcp_connection(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    allowed_tools_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    allowed_resources_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    configuration_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by uuid NOT NULL REFERENCES app_user(id),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id, connection_id),
    CONSTRAINT ck_agent_project_mcp_version CHECK (version >= 0)
);

CREATE INDEX idx_agent_project_mcp_enabled ON agent_project_mcp_binding(project_id, enabled);
