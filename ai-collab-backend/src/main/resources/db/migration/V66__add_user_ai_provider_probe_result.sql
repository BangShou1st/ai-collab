-- V66: persist capability probe results for user AI providers.
-- One row per (provider, probe_mode). STALE is derived at read time:
-- when user_ai_provider.updated_at differs from tested_config_updated_at,
-- the stored verdict no longer describes the current configuration.

CREATE TABLE user_ai_provider_probe_result (
    id BIGSERIAL PRIMARY KEY,
    provider_id UUID NOT NULL REFERENCES user_ai_provider(id) ON DELETE CASCADE,
    probe_mode VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    model_name VARCHAR(160),
    detail TEXT,
    error_code VARCHAR(64),
    latency_ms BIGINT,
    tested_config_updated_at TIMESTAMPTZ NOT NULL,
    tested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_probe_result_provider_mode UNIQUE (provider_id, probe_mode),
    CONSTRAINT ck_probe_result_mode CHECK (
        probe_mode IN ('CONNECTIVITY', 'PLANNING_JSON', 'STREAMING', 'NATIVE_TOOLS')
    ),
    CONSTRAINT ck_probe_result_status CHECK (
        status IN ('PASS', 'FAIL', 'NOT_SUPPORTED', 'INCONCLUSIVE')
    )
);

CREATE INDEX idx_probe_result_provider ON user_ai_provider_probe_result (provider_id);
