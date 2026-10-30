-- V71: Dynamic system configuration and audit log tables
CREATE TABLE system_configuration (
    id UUID PRIMARY KEY,
    agent_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    chat_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    worker_concurrency INTEGER NOT NULL DEFAULT 2,
    worker_delay_ms BIGINT NOT NULL DEFAULT 1000,
    schedule_delay_ms BIGINT NOT NULL DEFAULT 30000,
    system_maintenance_mode BOOLEAN NOT NULL DEFAULT FALSE,
    system_maintenance_message VARCHAR(500) NOT NULL DEFAULT '',
    public_registration_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    default_chat_model VARCHAR(120) NOT NULL DEFAULT '',
    default_agent_model VARCHAR(120) NOT NULL DEFAULT '',
    rag_retrieval_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.55,
    rag_max_sources INTEGER NOT NULL DEFAULT 12,
    version BIGINT NOT NULL DEFAULT 1,
    updated_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_config_concurrency CHECK (worker_concurrency BETWEEN 1 AND 4),
    CONSTRAINT ck_system_config_worker_delay CHECK (worker_delay_ms BETWEEN 200 AND 60000),
    CONSTRAINT ck_system_config_schedule_delay CHECK (schedule_delay_ms BETWEEN 1000 AND 300000),
    CONSTRAINT ck_system_config_rag_threshold CHECK (rag_retrieval_threshold BETWEEN 0.0 AND 1.0),
    CONSTRAINT ck_system_config_rag_sources CHECK (rag_max_sources BETWEEN 1 AND 50)
);

-- Seed initial default configuration row (agent and chat default to safe false)
INSERT INTO system_configuration (
    id, agent_enabled, chat_enabled, worker_concurrency, worker_delay_ms, schedule_delay_ms,
    system_maintenance_mode, system_maintenance_message, public_registration_enabled,
    default_chat_model, default_agent_model, rag_retrieval_threshold, rag_max_sources,
    version, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    FALSE, FALSE, 2, 1000, 30000,
    FALSE, '', TRUE,
    '', '', 0.55, 12,
    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
);

CREATE TABLE system_config_audit (
    id UUID PRIMARY KEY,
    config_version BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    changed_by UUID,
    changed_by_name VARCHAR(120),
    change_summary TEXT,
    config_snapshot JSONB NOT NULL,
    applied_status VARCHAR(32) NOT NULL DEFAULT 'APPLIED',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_system_config_audit_action CHECK (action IN ('INITIALIZE', 'UPDATE', 'RESTORE')),
    CONSTRAINT ck_system_config_audit_status CHECK (applied_status IN ('APPLIED', 'FAILED', 'PENDING'))
);

CREATE INDEX idx_system_config_audit_created ON system_config_audit (created_at DESC);
CREATE INDEX idx_system_config_audit_version ON system_config_audit (config_version DESC);

-- Record the initial seed in audit
INSERT INTO system_config_audit (
    id, config_version, action, change_summary, config_snapshot, applied_status, created_at
) VALUES (
    '00000000-0000-0000-0000-000000000002',
    1,
    'INITIALIZE',
    '系统初始化默认配置（安全关闭 Agent 与 Chat）',
    '{"agentEnabled": false, "chatEnabled": false, "workerConcurrency": 2, "workerDelayMs": 1000, "scheduleDelayMs": 30000, "systemMaintenanceMode": false, "systemMaintenanceMessage": "", "publicRegistrationEnabled": true, "defaultChatModel": "", "defaultAgentModel": "", "ragRetrievalThreshold": 0.55, "ragMaxSources": 12}'::jsonb,
    'APPLIED',
    CURRENT_TIMESTAMP
);
