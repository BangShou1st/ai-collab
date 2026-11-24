-- 跨模块证据契约：允许任务规划持久化工序传入（如 Agent 会话）并经服务端重验后的种子证据清单
ALTER TABLE ai_task_plan
    ADD COLUMN IF NOT EXISTS seed_evidence_json JSONB NOT NULL DEFAULT '[]'::jsonb;
