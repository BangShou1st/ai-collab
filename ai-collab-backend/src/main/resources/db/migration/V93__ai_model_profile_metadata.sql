-- V93: 模型身份档案补齐 model_key / display_name / version / sha256 / license。
-- Rerank 的 context_window_tokens 表示最大输入长度，max_output_tokens 保持 NULL。

ALTER TABLE ai_model_profile
    ADD COLUMN model_key VARCHAR(160),
    ADD COLUMN display_name VARCHAR(160),
    ADD COLUMN model_version VARCHAR(80),
    ADD COLUMN sha256 CHAR(64),
    ADD COLUMN license VARCHAR(160);

UPDATE ai_model_profile
SET model_key = model_name,
    display_name = model_name
WHERE model_key IS NULL OR display_name IS NULL;

ALTER TABLE ai_model_profile ALTER COLUMN model_key SET NOT NULL;
ALTER TABLE ai_model_profile ALTER COLUMN display_name SET NOT NULL;

ALTER TABLE ai_model_profile DROP CONSTRAINT ck_ai_model_profile_source;
ALTER TABLE ai_model_profile
    ADD CONSTRAINT ck_ai_model_profile_source CHECK (
        source IN ('UNVERIFIED', 'PROVIDER_DOCUMENT', 'ADMIN_VERIFIED', 'LOCAL_FILE')
    );

ALTER TABLE ai_model_profile DROP CONSTRAINT ck_ai_model_profile_verified;
ALTER TABLE ai_model_profile
    ADD CONSTRAINT ck_ai_model_profile_verified CHECK (
        source NOT IN ('ADMIN_VERIFIED', 'LOCAL_FILE') OR verified_at IS NOT NULL
    ),
    ADD CONSTRAINT ck_ai_model_profile_sha256 CHECK (
        sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'
    );

CREATE INDEX idx_ai_model_profile_key ON ai_model_profile (model_key);

COMMENT ON COLUMN ai_model_profile.model_key IS '稳定模型键，例如 Qwen3-Reranker-0.6B；不是文件路径';
COMMENT ON COLUMN ai_model_profile.model_version IS '模型版本或发布日期';
COMMENT ON COLUMN ai_model_profile.sha256 IS '模型文件校验和，仅摘要，不是文件路径';
COMMENT ON COLUMN ai_model_profile.license IS '模型许可证标识';
