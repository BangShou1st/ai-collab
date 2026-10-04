-- 孤儿模型用量结算账本：模型调用已发生但状态机已离开 RUNNING（取消/竞争）时，
-- 以稳定调用身份（run_id + call_id）幂等入账；重复回调、恢复与取消竞争只入账一次。
-- used 保持预算语义（封顶），actual 如实累计，可超出运行上限（V56 语义不变）。
-- V56 将历史行的 actual 用 used 回填，是历史下界；本表只记录新增的结算行，
-- 与 agent_step 步骤账本互补，供对账使用。
CREATE TABLE agent_usage_settlement (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    call_id VARCHAR(160) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    input_tokens_actual BIGINT,
    output_tokens_actual BIGINT,
    usage_basis VARCHAR(16) NOT NULL,
    latency_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_usage_settlement_basis
        CHECK (usage_basis IN ('PROVIDER', 'ESTIMATED', 'UNKNOWN')),
    CONSTRAINT uq_agent_usage_settlement_call UNIQUE (run_id, call_id)
);

CREATE INDEX idx_agent_usage_settlement_run ON agent_usage_settlement(run_id);
