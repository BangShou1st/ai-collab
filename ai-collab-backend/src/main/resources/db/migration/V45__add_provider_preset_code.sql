-- V45: provider preset (OpenCode Zen Free product preset, wire stays OPENAI_COMPATIBLE).
-- preset_code NULL = existing custom provider. Preset rows trust server registry, not user URLs.
ALTER TABLE user_ai_provider ADD COLUMN preset_code VARCHAR(64);
ALTER TABLE user_ai_provider ADD CONSTRAINT ck_user_ai_provider_preset CHECK (preset_code IS NULL OR preset_code IN ('OPENCODE_ZEN_FREE'));
CREATE UNIQUE INDEX uk_user_ai_provider_user_preset ON user_ai_provider (user_id, preset_code) WHERE preset_code IS NOT NULL;
