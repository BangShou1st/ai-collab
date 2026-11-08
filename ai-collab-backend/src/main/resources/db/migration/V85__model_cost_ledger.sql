-- V85: 模型成本台账（非计费，设计文档 agent-fanout-budget-and-agentic-planning.md §4.4）。
-- 用户级模型配置（user_ai_provider）补充单价（用户自填，NULL = 不计成本）；
-- 每次 usage 记账同步把该次记账的成本写到 agent_run_event.cost_amount（不建新表）。
-- 非目标：账单、配额扣费、价格自动获取。

ALTER TABLE user_ai_provider
    ADD COLUMN price_per_1k_input NUMERIC(10,6),
    ADD COLUMN price_per_1k_output NUMERIC(10,6);

-- 口径：每 1000 token 的单价，由用户自填；NULL = 该配置不计成本。
-- 单价不是"影响真实模型请求行为的参数"，不推进 capability_config_version。
ALTER TABLE user_ai_provider
    ADD CONSTRAINT ck_user_ai_provider_price_input CHECK (price_per_1k_input IS NULL OR price_per_1k_input >= 0),
    ADD CONSTRAINT ck_user_ai_provider_price_output CHECK (price_per_1k_output IS NULL OR price_per_1k_output >= 0);

COMMENT ON COLUMN user_ai_provider.price_per_1k_input IS '每 1000 输入 token 单价（用户自填，NULL = 不计成本）';
COMMENT ON COLUMN user_ai_provider.price_per_1k_output IS '每 1000 输出 token 单价（用户自填，NULL = 不计成本）';

-- 成本挂在事件行上：MODEL_COMPLETED 承载模型轮成本；摘要/迟到结算等无独立事件行的
-- 记账批次按累加语义并入该运行最近一次 MODEL_COMPLETED（会话级求和不受挂载点影响）。
-- NULL = 未计成本（价格未配置 / 运行未关联配置 / UNKNOWN 用量不虚构零费用 / 附属记账失败）。
ALTER TABLE agent_run_event ADD COLUMN cost_amount NUMERIC(12,6);

COMMENT ON COLUMN agent_run_event.cost_amount IS '该事件对应记账批次的成本 = bookedTokens/1000 × 单价（6 位小数）；NULL = 未计成本';
