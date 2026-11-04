-- V77: shared AI provider and admin system notifications

-- 全站共享 AI 供应商表
CREATE TABLE shared_ai_provider (
    id UUID PRIMARY KEY,
    source_provider_id UUID NOT NULL,
    publisher_user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(80) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL DEFAULT '/v1/chat/completions',
    encrypted_api_key TEXT NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED',
    temperature DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    max_output_tokens INTEGER NOT NULL DEFAULT 4096,
    capabilities VARCHAR(500) NOT NULL DEFAULT 'CHAT,STREAMING,USAGE',
    capability_config_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_shared_ai_provider_source UNIQUE (source_provider_id),
    CONSTRAINT ck_shared_ai_provider_type CHECK (
        provider_type IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI')
    ),
    CONSTRAINT ck_shared_ai_provider_status CHECK (
        status IN ('PUBLISHED', 'REVOKED')
    ),
    CONSTRAINT ck_shared_ai_provider_output_tokens CHECK (max_output_tokens BETWEEN 1 AND 131072),
    CONSTRAINT ck_shared_ai_provider_temperature CHECK (temperature BETWEEN 0 AND 2)
);

CREATE INDEX idx_shared_ai_provider_status ON shared_ai_provider(status, enabled);

-- 调整用户用途绑定表：解除仅能绑定到个人 provider 的外键约束，引入 is_shared 标识
ALTER TABLE user_model_purpose_assignment
    DROP CONSTRAINT IF EXISTS user_model_purpose_assignment_provider_id_fkey;

ALTER TABLE user_model_purpose_assignment
    ADD COLUMN IF NOT EXISTS is_shared BOOLEAN NOT NULL DEFAULT FALSE;

-- 管理员专属系统通知表
CREATE TABLE admin_notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category VARCHAR(50) NOT NULL,
    level VARCHAR(20) NOT NULL DEFAULT 'INFO',
    title VARCHAR(200) NOT NULL,
    content TEXT,
    target_path VARCHAR(255),
    dedupe_key VARCHAR(120),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_admin_notification_level CHECK (level IN ('INFO', 'WARNING', 'ERROR'))
);

CREATE INDEX idx_admin_notification_unread ON admin_notification (is_read, created_at DESC);
CREATE INDEX idx_admin_notification_dedupe ON admin_notification (dedupe_key);
