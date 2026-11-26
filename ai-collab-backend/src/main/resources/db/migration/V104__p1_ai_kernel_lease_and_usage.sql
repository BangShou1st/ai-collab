-- P1 Python AI 内核（契约 docs/design/p1-ai-kernel-contract-2026-11-26.md §2.4、§3）。
-- 只触及 public schema；不读写 ai_index / ai_runtime。

-- §2.4 凭据租借审计：只记录"谁、为何、借了哪个提供商的哪个模型、何时到期"，
-- 绝不存任何密钥或密文。provider_type 用于用量回报时回填 ai_call_log.provider。
CREATE TABLE ai_credential_lease (
    id            uuid PRIMARY KEY,
    user_id       uuid REFERENCES app_user(id) ON DELETE SET NULL,
    project_id    uuid REFERENCES project(id) ON DELETE SET NULL,
    purpose       varchar(32)  NOT NULL,
    provider_id   uuid         NOT NULL,
    provider_type varchar(32)  NOT NULL,
    model         varchar(120) NOT NULL,
    task_id       uuid,
    run_id        uuid,
    issued_at     timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    CONSTRAINT ck_ai_credential_lease_purpose CHECK (purpose IN
        ('KNOWLEDGE_CHAT', 'AGENT', 'PLANNING', 'QUERY_PLAN', 'MEMORY_EXTRACTION', 'EMBEDDING')),
    CONSTRAINT ck_ai_credential_lease_window CHECK (expires_at > issued_at)
);

CREATE INDEX idx_ai_credential_lease_user_time ON ai_credential_lease (user_id, issued_at DESC);
CREATE INDEX idx_ai_credential_lease_task ON ai_credential_lease (task_id) WHERE task_id IS NOT NULL;

COMMENT ON TABLE ai_credential_lease IS '凭据租借审计；不含任何密钥或密文';

-- §3 用量回报：call_id 幂等键、lease 关联、调用方类型；状态 CHECK 增加 CANCELED。
ALTER TABLE ai_call_log
    ADD COLUMN call_id     uuid,
    ADD COLUMN lease_id    uuid REFERENCES ai_credential_lease(id) ON DELETE SET NULL,
    ADD COLUMN caller_type varchar(16) NOT NULL DEFAULT 'JAVA',
    ADD CONSTRAINT uk_ai_call_log_call_id UNIQUE (call_id),
    ADD CONSTRAINT ck_ai_call_log_caller_type CHECK (caller_type IN ('JAVA', 'PYTHON'));

ALTER TABLE ai_call_log DROP CONSTRAINT ck_ai_call_status;
ALTER TABLE ai_call_log
    ADD CONSTRAINT ck_ai_call_status CHECK (status IN
        ('SUCCESS', 'TIMEOUT', 'QUOTA_EXCEEDED', 'PROVIDER_ERROR', 'INVALID_OUTPUT', 'CANCELED'));
