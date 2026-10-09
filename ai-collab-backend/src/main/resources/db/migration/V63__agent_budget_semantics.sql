-- V63: 预算语义兼容标记（模型推进预算与工具调用预算分离）。
-- COMBINED（旧语义，既有行默认）：工具结果逐项占用推进步（steps_used），历史运行
--   恢复/暂停续跑/崩溃接管按原含义继续累计，不重算、不重解释。
-- SEPARATED（新语义，新根运行默认）：一次模型轮计一次推进、最终回答落库保留一次收口；
--   普通工具结果只计 tool_calls_used，持久化步骤/事件/invocation 全部保留。
-- 子运行（两种委派路径）继承父运行的语义；终态手动重试派生的新运行采用 SEPARATED。
ALTER TABLE agent_run
    ADD COLUMN budget_semantics varchar(16) NOT NULL DEFAULT 'COMBINED';
ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_budget_semantics
    CHECK (budget_semantics IN ('COMBINED', 'SEPARATED'));
