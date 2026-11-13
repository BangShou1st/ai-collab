-- V98: D4 最小准入复用既有探针结果表，新增 PUBLISH_ADMISSION 模式。
-- 仅调整 CHECK 约束，不新增表；摘要写入 detail（JSON），latency_ms 存 P95。
-- 旧结论不受影响；发布时必须校验 tested_config_version 与当前配置版本一致。

ALTER TABLE user_ai_provider_probe_result
    DROP CONSTRAINT ck_probe_result_mode;

ALTER TABLE user_ai_provider_probe_result
    ADD CONSTRAINT ck_probe_result_mode CHECK (
        probe_mode IN (
            'CONNECTIVITY',
            'PLANNING_JSON',
            'STREAMING',
            'NATIVE_TOOLS',
            'PUBLISH_ADMISSION'
        )
    );

COMMENT ON COLUMN user_ai_provider_probe_result.detail IS
    '探针/准入摘要；PUBLISH_ADMISSION 存 JSON（samples/p50/p95/timeoutCount/failureCount/configVersion）';
