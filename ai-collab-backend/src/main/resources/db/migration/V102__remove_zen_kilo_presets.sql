-- V102: 移除 OpenCode Zen / Kilo Gateway 预设模型配置及 preset_code 列。
-- 依赖现有触发器 trg_cascade_user_ai_provider_delete (V77) 与 ON DELETE CASCADE (V66)
-- 自动级联清理个人用途绑定和能力探针记录，显式清理前置兜底防孤儿行。
DELETE FROM user_model_purpose_assignment
WHERE is_shared = FALSE
  AND provider_id IN (
      SELECT id FROM user_ai_provider
      WHERE preset_code IN ('OPENCODE_ZEN_FREE', 'KILO_GATEWAY_FREE')
  );

DELETE FROM user_ai_provider
WHERE preset_code IN ('OPENCODE_ZEN_FREE', 'KILO_GATEWAY_FREE');

ALTER TABLE user_ai_provider DROP CONSTRAINT IF EXISTS ck_user_ai_provider_preset;
DROP INDEX IF EXISTS uk_user_ai_provider_user_preset;
ALTER TABLE user_ai_provider DROP COLUMN IF EXISTS preset_code;
