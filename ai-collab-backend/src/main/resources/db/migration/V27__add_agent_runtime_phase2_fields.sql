-- Phase 2: 添加 Runtime、Context、Skills 所需字段
-- 添加 plan_json、page_context_json、skill_code 字段到 agent_run 表

ALTER TABLE agent_run
    ADD COLUMN plan_json jsonb,
    ADD COLUMN page_context_json jsonb,
    ADD COLUMN skill_code varchar(80);

-- 添加 MODEL_TURN 类型到 agent_step 的 type 检查
ALTER TABLE agent_step DROP CONSTRAINT IF EXISTS ck_agent_step_type;
ALTER TABLE agent_step ADD CONSTRAINT ck_agent_step_type CHECK (type IN (
    'MODEL_REQUEST', 'MODEL_DECISION', 'MODEL_TURN', 'TOOL_CALL_PROPOSED',
    'TOOL_CALL_COMPLETED', 'APPROVAL_REQUESTED', 'APPROVAL_RESOLVED',
    'DELEGATION_REQUESTED', 'DELEGATION_COMPLETED', 'FINAL_ANSWER', 'ERROR'
));

-- 添加 model_provider 和 model_name 到 agent_step
ALTER TABLE agent_step
    ADD COLUMN model_provider varchar(80),
    ADD COLUMN model_name varchar(120);

-- 添加索引
CREATE INDEX idx_agent_run_skill ON agent_run(skill_code) WHERE skill_code IS NOT NULL;
