-- V92: 管理端共享 Provider 的能力元数据（Lane A / RERANK）
-- 复用 shared_ai_provider 作为四层配置中的 Provider 层，但不改变个人 Provider 行为。

ALTER TABLE shared_ai_provider ALTER COLUMN source_provider_id DROP NOT NULL;
ALTER TABLE shared_ai_provider ALTER COLUMN encrypted_api_key DROP NOT NULL;
ALTER TABLE shared_ai_provider ALTER COLUMN model_name DROP NOT NULL;

ALTER TABLE shared_ai_provider
    ADD COLUMN protocol VARCHAR(32) NOT NULL DEFAULT 'OPENAI_CHAT',
    ADD COLUMN auth_type VARCHAR(16) NOT NULL DEFAULT 'API_KEY',
    ADD COLUMN network_zone VARCHAR(16) NOT NULL DEFAULT 'public',
    ADD COLUMN health_path VARCHAR(512) NOT NULL DEFAULT '/health';

ALTER TABLE shared_ai_provider DROP CONSTRAINT ck_shared_ai_provider_type;
ALTER TABLE shared_ai_provider
    ADD CONSTRAINT ck_shared_ai_provider_type CHECK (
        provider_type IN ('OPENAI_COMPATIBLE', 'ANTHROPIC', 'GEMINI', 'LOCAL_CUSTOM')
    );

ALTER TABLE shared_ai_provider
    ADD CONSTRAINT ck_shared_ai_provider_protocol CHECK (
        protocol IN (
            'OPENAI_CHAT',
            'ANTHROPIC_MESSAGES',
            'GEMINI_GENERATE_CONTENT',
            'OPENAI_EMBEDDINGS',
            'TEI_EMBEDDINGS',
            'RERANK_HTTP',
            'LLAMACPP_RERANK',
            'TEI_RERANK'
        )
    ),
    ADD CONSTRAINT ck_shared_ai_provider_auth_type CHECK (
        auth_type IN ('NONE', 'API_KEY', 'BEARER', 'CUSTOM_HEADER')
    ),
    ADD CONSTRAINT ck_shared_ai_provider_network_zone CHECK (
        network_zone IN ('local', 'internal', 'public')
    ),
    ADD CONSTRAINT ck_shared_ai_provider_health_path CHECK (
        health_path = '' OR health_path LIKE '/%'
    ),
    ADD CONSTRAINT ck_shared_ai_provider_local_zone CHECK (
        provider_type <> 'LOCAL_CUSTOM' OR network_zone = 'local'
    );

-- 仅约束管理员直接创建的能力 Provider；历史上由个人 Provider 发布的共享行可能重名。
CREATE UNIQUE INDEX uk_shared_ai_provider_direct_name
    ON shared_ai_provider (lower(name))
    WHERE source_provider_id IS NULL;

CREATE INDEX idx_shared_ai_provider_protocol
    ON shared_ai_provider (protocol, enabled);

COMMENT ON COLUMN shared_ai_provider.protocol IS 'Provider 主协议，例如 LLAMACPP_RERANK；一个 Provider 只声明一个主协议';
COMMENT ON COLUMN shared_ai_provider.auth_type IS 'NONE/API_KEY/BEARER/CUSTOM_HEADER；密钥只加密存储，不回传前端';
COMMENT ON COLUMN shared_ai_provider.network_zone IS 'local/internal/public；本地 Provider 只能是 local';
COMMENT ON COLUMN shared_ai_provider.health_path IS '健康检查相对路径；不包含模型文件路径或密钥';
