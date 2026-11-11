-- V91: W5 Memory 提取器 Job 骨架（冻结契约 §3.1）。
--
-- 归属：Java 是 ai_job / ai_job_attempt / ai_job_result 的唯一写者；Python 不建 Job 表、
-- 不调度、不决定重试、不发布候选，只能通过 /internal/ai/v1/jobs/{id}/* 读写有界快照与结果。
-- 本波 memory.job.enabled=false：没有自动 dispatcher 进入生产路径，也没有候选发布路径。
--
-- 红线：
--   * 表名/列名/约束名与冻结契约一致，不改历史迁移；
--   * ai_job_result.review_status 本波只允许停在 PENDING_REVIEW（ACCEPTED/REJECTED 的写入路径
--     与“候选 → agent_memory”的转换均不存在）；
--   * status/job_type 用 CHECK 兜底，应用层状态机同时校验非法跃迁。

CREATE TABLE ai_job (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES project(id),
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    created_by UUID REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_ai_job_status CHECK (status IN
      ('QUEUED','LEASED','RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED','CANCELED','EXPIRED')),
    CONSTRAINT ck_ai_job_type CHECK (job_type IN ('MEMORY_EXTRACTION'))
);
CREATE INDEX idx_ai_job_queue ON ai_job (status, next_attempt_at);
CREATE INDEX idx_ai_job_project ON ai_job (project_id, created_at DESC);

CREATE TABLE ai_job_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES ai_job(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    input_tokens INTEGER,
    output_tokens INTEGER,
    CONSTRAINT uk_ai_job_attempt UNIQUE (job_id, attempt_no),
    CONSTRAINT ck_ai_job_attempt_status CHECK (status IN
      ('RUNNING','SUCCEEDED','FAILED_RETRYABLE','FAILED','EXPIRED'))
);

CREATE TABLE ai_job_result (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id UUID NOT NULL REFERENCES ai_job(id) ON DELETE CASCADE,
    attempt_no INTEGER NOT NULL,
    result_kind VARCHAR(32) NOT NULL,
    dedupe_key CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ai_job_result_dedupe UNIQUE (job_id, dedupe_key),
    CONSTRAINT ck_ai_job_result_kind CHECK (result_kind IN ('MEMORY_CANDIDATE')),
    CONSTRAINT ck_ai_job_result_review CHECK (review_status IN ('PENDING_REVIEW','ACCEPTED','REJECTED'))
);
