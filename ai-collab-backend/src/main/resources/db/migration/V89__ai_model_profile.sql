-- V89: 全局模型能力档案（AC-P1 / 设计 §4.1）
-- 以 provider_type + 归一化 endpoint(base_url + api_path) + model_name 作为全局身份，
-- 由管理员维护经核实的上下文窗口；运行时优先使用档案，未命中再回退 agent.context.window-overrides。
-- 档案是模型/端点级事实，不挂在某个用户的配置行上。
CREATE TABLE ai_model_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_key CHAR(64) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    api_path VARCHAR(512) NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    context_window_tokens INTEGER NOT NULL,
    max_output_tokens INTEGER,
    supports_usage BOOLEAN NOT NULL DEFAULT FALSE,
    tokenizer_kind VARCHAR(32),
    pricing_version VARCHAR(64),
    source VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED',
    verified_at TIMESTAMPTZ,
    verified_by UUID REFERENCES app_user(id),
    created_by UUID NOT NULL REFERENCES app_user(id),
    updated_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_model_profile_key UNIQUE (profile_key),
    CONSTRAINT ck_ai_model_profile_window CHECK (context_window_tokens > 0),
    CONSTRAINT ck_ai_model_profile_output CHECK (max_output_tokens IS NULL OR max_output_tokens > 0),
    CONSTRAINT ck_ai_model_profile_source CHECK (source IN ('UNVERIFIED', 'PROVIDER_DOCUMENT', 'ADMIN_VERIFIED')),
    CONSTRAINT ck_ai_model_profile_verified CHECK (source <> 'ADMIN_VERIFIED' OR verified_at IS NOT NULL)
);

CREATE INDEX idx_ai_model_profile_model ON ai_model_profile (provider_type, model_name);

COMMENT ON TABLE ai_model_profile IS '全局模型能力档案：经核实的上下文窗口与能力元数据（AC-P1）';
COMMENT ON COLUMN ai_model_profile.profile_key IS 'sha256(provider_type|归一化 base_url|归一化 api_path|小写 model_name)';
COMMENT ON COLUMN ai_model_profile.source IS 'UNVERIFIED=未核实；PROVIDER_DOCUMENT=依据服务商文档；ADMIN_VERIFIED=管理员核实';
