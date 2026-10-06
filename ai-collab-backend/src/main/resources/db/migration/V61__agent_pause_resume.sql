-- V61: Agent 主动暂停与输入续跑。
-- PAUSED 是非终态：排队/重试等待中的运行原子转入；运行中的运行先落暂停意图，
-- 由 worker 在动作边界确认（结清已确认执行段、释放租约），未消费的模型轮次与
-- PENDING 工具调用全部保留，恢复后按原身份继续。pause_requested_at 记录暂停意图
-- 落库时间；RUNNING 上的意图写入不递增 version（不使在途响应的 CAS 失配）。
ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_status;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_status CHECK (status IN (
    'CREATED', 'QUEUED', 'RUNNING', 'WAITING_FOR_APPROVAL', 'WAITING_FOR_USER_INPUT',
    'PAUSED', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED', 'CANCELED', 'BUDGET_EXCEEDED'
));
ALTER TABLE agent_run ADD COLUMN pause_requested_at TIMESTAMPTZ;

-- 控制事件类型：暂停请求/已暂停/已继续（Recorder 原子事件路径同事务写入）
ALTER TABLE agent_run_event DROP CONSTRAINT ck_agent_run_event_type;
ALTER TABLE agent_run_event ADD CONSTRAINT ck_agent_run_event_type CHECK (type IN (
    'RUN_CREATED','CONTEXT_CAPTURED','SKILL_SELECTED',
    'PLAN_CREATED','PLAN_UPDATED','MODEL_STARTED','MODEL_COMPLETED',
    'TOOL_CALL_PROPOSED','TOOL_CALL_STARTED','TOOL_CALL_COMPLETED','TOOL_CALL_FAILED',
    'APPROVAL_REQUESTED','APPROVAL_APPROVED','APPROVAL_REJECTED',
    'APPROVAL_EXPIRED','APPROVAL_UPDATED',
    'RESULT_VERIFIED','RUN_RETRY_SCHEDULED',
    'RUN_PAUSE_REQUESTED','RUN_PAUSED','RUN_RESUMED',
    'RUN_CANCELED','RUN_SUCCEEDED','RUN_FAILED','RUN_BUDGET_EXCEEDED',
    'WAITING_FOR_USER_INPUT'
));
