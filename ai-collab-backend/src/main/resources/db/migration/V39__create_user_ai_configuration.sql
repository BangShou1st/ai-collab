-- V39: user-scoped AI provider configuration (V2 AI Configuration).
-- Each user manages their own LLM providers; purpose assignment lives in V40.
-- Resolution: purpose assignment wins, else is_default provider, else unconfigured.

CREATE TABLE user_ai_provider (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL DEFAULT '/v1/chat/completions',
    encrypted_api_key TEXT,
    model_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    temperature DOUBLE PRECISION NOT NULL DEFAULT 0.2,
    max_output_tokens INTEGER NOT NULL DEFAULT 1200,
    capabilities VARCHAR(500) NOT NULL DEFAULT 'CHAT,STREAMING,USAGE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_ai_provider_user_name UNIQUE (user_id, name),
    CONSTRAINT ck_user_ai_provider_type CHECK (
        provider_type IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')
    ),
    CONSTRAINT ck_user_ai_provider_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 131072),
    CONSTRAINT ck_user_ai_provider_temperature CHECK (temperature BETWEEN 0 AND 2)
);

-- Each user owns at most one default provider; non-default rows are unrestricted.
CREATE UNIQUE INDEX uk_user_ai_provider_user_default ON user_ai_provider (user_id) WHERE is_default;
CREATE INDEX idx_user_ai_provider_user ON user_ai_provider (user_id);
