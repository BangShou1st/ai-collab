-- V94: 能力档案（CHAT/EMBEDDING/RERANK）与不可变发布版本。

CREATE TABLE ai_model_capability_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    model_profile_id UUID NOT NULL REFERENCES ai_model_profile(id),
    provider_id UUID NOT NULL REFERENCES shared_ai_provider(id),
    capability VARCHAR(16) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    settings_json JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INTEGER NOT NULL,
    source VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    verified_at TIMESTAMPTZ,
    verified_by UUID REFERENCES app_user(id),
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_model_capability_profile_version
        UNIQUE (model_profile_id, capability, version),
    CONSTRAINT ck_ai_model_capability_profile_capability CHECK (
        capability IN ('CHAT', 'EMBEDDING', 'RERANK')
    ),
    CONSTRAINT ck_ai_model_capability_profile_protocol CHECK (
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
    CONSTRAINT ck_ai_model_capability_profile_status CHECK (
        status IN ('DRAFT', 'TESTED', 'PUBLISHED', 'DISABLED')
    ),
    CONSTRAINT ck_ai_model_capability_profile_version CHECK (version > 0),
    CONSTRAINT ck_ai_model_capability_profile_source CHECK (
        source IN ('UNVERIFIED', 'PROVIDER_DOCUMENT', 'ADMIN_VERIFIED', 'LOCAL_FILE')
    ),
    CONSTRAINT ck_ai_model_capability_profile_verified CHECK (
        status NOT IN ('TESTED', 'PUBLISHED') OR verified_at IS NOT NULL
    )
);

-- 同一模型/能力最多一个 PUBLISHED 版本。
CREATE UNIQUE INDEX uk_ai_model_capability_profile_published
    ON ai_model_capability_profile (model_profile_id, capability)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_ai_model_capability_profile_provider
    ON ai_model_capability_profile (provider_id, capability, status);

-- 已发布 settings 不可原地修改；发布/回滚只允许改 status/updated_* 等元数据。
CREATE OR REPLACE FUNCTION reject_published_capability_settings_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' AND NEW.settings_json IS DISTINCT FROM OLD.settings_json THEN
        RAISE EXCEPTION 'published capability settings are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reject_published_capability_settings_update
    BEFORE UPDATE ON ai_model_capability_profile
    FOR EACH ROW
    EXECUTE FUNCTION reject_published_capability_settings_update();

COMMENT ON TABLE ai_model_capability_profile IS '模型在具体 Provider 上的能力配置；settings_json 由 Java JSON Schema 严格校验';
COMMENT ON COLUMN ai_model_capability_profile.version IS '同一模型/能力内单调递增的不可变版本号';
COMMENT ON COLUMN ai_model_capability_profile.settings_json IS 'capability-specific 参数，不接受其它能力的字段';
