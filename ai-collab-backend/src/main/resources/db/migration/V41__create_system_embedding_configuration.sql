-- V41: system-scoped embedding infrastructure (V2 AI Configuration).
-- The whole platform shares one active semantic configuration; only systemAdmin manages it.
-- Runtime resolution and legacy fingerprint backfill arrive in V43.

CREATE TABLE system_embedding_config (
    id UUID PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL DEFAULT '/v1/embeddings',
    encrypted_api_key TEXT,
    model_name VARCHAR(160) NOT NULL,
    dimensions INTEGER NOT NULL,
    batch_size INTEGER NOT NULL DEFAULT 16,
    fingerprint VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_embedding_provider CHECK (
        provider IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')
    ),
    CONSTRAINT ck_system_embedding_dimensions CHECK (dimensions BETWEEN 1 AND 4096),
    CONSTRAINT ck_system_embedding_batch_size CHECK (batch_size BETWEEN 1 AND 256)
);
CREATE UNIQUE INDEX uk_system_embedding_single_active ON system_embedding_config ((enabled)) WHERE enabled;
