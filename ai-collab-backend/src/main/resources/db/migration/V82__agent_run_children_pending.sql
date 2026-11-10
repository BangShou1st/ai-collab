-- V82: 子 Agent 并行 fan-out 的等待语义（设计文档
-- docs/design/agent-fanout-budget-and-agentic-planning.md §3.1）。
--
-- 父运行从"委派受理后转 QUEUED 挂起"改为"带待决子计数的显式等待态"：
-- agent_run 新增 children_pending；委派受理时 +1（同一事务 children_used+1），
-- 父运行保持 RUNNING 但立即释放租约，"等待子运行"由 children_pending > 0 表达，
-- 不引入新状态字段。子运行终态时 children_pending-1（CAS 防负），归零时父运行
-- 重新排队（resumeParent 单点收口）。
--
-- 同时把 ck_agent_run_budgets 的 max_children 上限 3→8（DROP 再 ADD，不改历史迁移），
-- 支撑一次 fan-out 最多 8 个并行子运行；默认值与 V2_ROOT_MAX_CHILDREN（3）不变。
-- 附带扩展 agent_run_event 类型 CHECK：新增 DELEGATION_COMPLETED / DELEGATION_FAILED
-- 父事件类型（resumeParent 单点收口时逐子写入，供事件流消费）。

ALTER TABLE agent_run ADD COLUMN children_pending integer NOT NULL DEFAULT 0;

ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_budgets;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_budgets CHECK (
    max_steps BETWEEN 1 AND 100
    AND max_tool_calls BETWEEN 0 AND 100
    AND max_children BETWEEN 0 AND 8
    AND max_input_tokens BETWEEN 1 AND 2000000
    AND max_output_tokens BETWEEN 1 AND 500000
    AND steps_used BETWEEN 0 AND max_steps
    AND tool_calls_used BETWEEN 0 AND max_tool_calls
    AND children_used BETWEEN 0 AND max_children
    AND children_pending BETWEEN 0 AND max_children
    AND input_tokens_used BETWEEN 0 AND max_input_tokens
    AND output_tokens_used BETWEEN 0 AND max_output_tokens
    AND estimated_cost >= 0
    AND retry_count BETWEEN 0 AND 10
    AND version >= 0
);

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
    'WAITING_FOR_USER_INPUT',
    'DELEGATION_COMPLETED','DELEGATION_FAILED'
));

COMMENT ON COLUMN agent_run.children_pending IS
    '并行 fan-out 待决子运行计数（V82）：委派受理 +1、子运行终态 -1（CAS 防负）；
     >0 表示父运行处于等待态（RUNNING 且租约已释放），claimNext 不领取；归零时父运行重新排队';
