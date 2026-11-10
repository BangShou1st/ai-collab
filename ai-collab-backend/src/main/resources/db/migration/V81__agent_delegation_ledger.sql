-- V81__agent_delegation_ledger.sql
-- 委派防重台账：同一会话下同一 objective（归一化哈希）的委派只允许一条非 CANCELED
-- 记录，防止父运行用"换个说法"的 objective 反复开新子 Agent（设计文档
-- docs/design/agent-fanout-budget-and-agentic-planning.md §3.2）。
-- 说明：设计文档草案中的 session_id/parent_run_id/child_run_id 为 BIGINT，
-- 实际 agent_run/agent_session 主键为 uuid，此处按真实 schema 采用 uuid 并加外键。

CREATE TABLE agent_delegation_ledger (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    parent_run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    child_run_id UUID NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    objective_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_delegation_ledger_status CHECK (status IN (
        'RUNNING', 'COMPLETED', 'FAILED', 'CANCELED'
    ))
);

CREATE INDEX idx_adl_session_hash
    ON agent_delegation_ledger (session_id, objective_hash);
CREATE INDEX idx_adl_parent
    ON agent_delegation_ledger (parent_run_id);

COMMENT ON TABLE agent_delegation_ledger IS
    '委派防重台账：记录每次父运行对子 Agent 的委派（objective 归一化哈希 + 状态），同一会话下同哈希存在非 CANCELED 记录时拒绝重复委派';
COMMENT ON COLUMN agent_delegation_ledger.objective_hash IS 'sha256(normalize(objective)) 的十六进制串，归一化规则见 AgentDelegationGuard';
COMMENT ON COLUMN agent_delegation_ledger.status IS '台账状态：RUNNING/COMPLETED/FAILED/CANCELED，随子运行终态收口';
