-- 添加 WAITING_FOR_USER_INPUT 状态支持多轮对话

-- 1. 更新 agent_run 表的 status CHECK 约束
ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_status CHECK (status IN (
    'CREATED', 'QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_USER_INPUT',
    'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED'
));

-- 2. 添加索引支持 WAITING_FOR_USER_INPUT 状态查询
CREATE INDEX idx_agent_run_waiting_for_input
    ON agent_run(status, created_at) WHERE status = 'WAITING_FOR_USER_INPUT';
