-- V65: 第二个产品预设 Kilo Gateway Free（wire 仍为 OPENAI_COMPATIBLE，不新增 provider_type）。
-- 扩展 preset_code 白名单，使同一用户可同时连接 OpenCode Zen 与 Kilo Gateway 两个免费预设；
-- 已有的 uk_user_ai_provider_user_preset(user_id, preset_code) 唯一索引天然支持两行并存。
ALTER TABLE user_ai_provider DROP CONSTRAINT ck_user_ai_provider_preset;
ALTER TABLE user_ai_provider ADD CONSTRAINT ck_user_ai_provider_preset
    CHECK (preset_code IS NULL OR preset_code IN ('OPENCODE_ZEN_FREE', 'KILO_GATEWAY_FREE'));
