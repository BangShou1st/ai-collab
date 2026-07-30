CREATE TABLE model_configuration (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL,
    encrypted_api_key TEXT,
    model_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    temperature DOUBLE PRECISION NOT NULL DEFAULT 0.2,
    max_output_tokens INTEGER NOT NULL DEFAULT 1200,
    capabilities VARCHAR(500) NOT NULL DEFAULT 'CHAT,STREAMING,USAGE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_model_configuration_name UNIQUE (name),
    CONSTRAINT ck_model_provider_type CHECK (
        provider_type IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')
    ),
    CONSTRAINT ck_model_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 131072),
    CONSTRAINT ck_model_temperature CHECK (temperature BETWEEN 0 AND 2)
);

CREATE TABLE model_purpose_assignment (
    purpose VARCHAR(32) PRIMARY KEY,
    model_configuration_id UUID NOT NULL REFERENCES model_configuration(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_model_purpose CHECK (
        purpose IN ('KNOWLEDGE_CHAT', 'AGENT', 'PLANNING')
    )
);
