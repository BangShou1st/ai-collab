-- P2-B2 W6-B: external read-only MCP provider admission.
-- This table is intentionally separate from the connection row so schema discovery and
-- credential rotation cannot silently preserve a previous provider review.

CREATE TABLE agent_mcp_provider_admission (
    connection_id uuid PRIMARY KEY REFERENCES agent_mcp_connection(id) ON DELETE CASCADE,
    provider_type varchar(32) NOT NULL
        CHECK (provider_type IN ('EXTERNAL_READ_ONLY', 'LOCAL_FIXTURE')),
    provider_owner varchar(200),
    data_scope varchar(2000),
    retention_policy varchar(1000),
    terms_url varchar(1000),
    least_privilege_scope varchar(1000),
    read_only_endpoint boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'APPROVED', 'REVOKED')),
    reviewed_by uuid,
    reviewed_at timestamptz,
    revoked_by uuid,
    revoked_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        status <> 'APPROVED'
        OR (
            provider_owner IS NOT NULL AND length(btrim(provider_owner)) > 0
            AND data_scope IS NOT NULL AND length(btrim(data_scope)) > 0
            AND retention_policy IS NOT NULL AND length(btrim(retention_policy)) > 0
            AND terms_url IS NOT NULL AND length(btrim(terms_url)) > 0
            AND least_privilege_scope IS NOT NULL AND length(btrim(least_privilege_scope)) > 0
            AND read_only_endpoint = true
            AND reviewed_by IS NOT NULL
            AND reviewed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_agent_mcp_provider_admission_status
    ON agent_mcp_provider_admission(status, updated_at);
