-- V74: 能力探针证据改用独立配置版本锚点，解除对 updated_at 的耦合。
-- 仅会改变真实模型请求行为的参数（provider_type/base_url/api_path/凭证/模型/温度/输出上限）
-- 变化时推进 capability_config_version；设默认、用途分配、显示名称、能力声明、启用开关
-- 的变化不再使既有探针结论失效（修复：设默认导致新旧默认双双被判 STALE）。

ALTER TABLE user_ai_provider
    ADD COLUMN capability_config_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE user_ai_provider_probe_result
    ADD COLUMN tested_config_version BIGINT;

-- 存量兼容：仍锚定在当前配置上的结论视为仍有效，回填为版本 0；
-- 已经过期的结论保持过期（无法还原其测试时的请求参数，不做无依据复活）。
UPDATE user_ai_provider_probe_result r
SET tested_config_version = p.capability_config_version
FROM user_ai_provider p
WHERE r.provider_id = p.id
  AND r.tested_config_updated_at = p.updated_at;
