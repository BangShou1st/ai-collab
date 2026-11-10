-- 会话聚合 token 预算（预算治理 v3，设计文档 docs/design/agent-fanout-budget-and-agentic-planning.md §4.1）
--
-- 背景：v2 预算语义下 run 的累计 token 上限为 NULL（只记账不管控）、子运行预算独立
-- 不从父切分，一次会话的聚合消耗只有步数间接约束。本迁移给 agent_session 补聚合硬顶。
--
-- max_aggregate_tokens：会话内所有运行（父 + 子）累计 token 的硬顶。
--   NULL = 不限（兼容存量会话，行为不变）；新会话由应用层在创建时写入配置默认值
--   （agent.budget.session-aggregate-token-cap，默认 2,000,000，见 AgentBudgetProperties）。
-- aggregate_tokens_used：会话聚合已用 token。父/子运行的所有模型轮与摘要调用在
--   AgentRunEventRecorder 的 usage 记账事务内，按 run 所属 session 以 bigint 版
--   agent_capped_add（V64）单语句封顶累计：cap 为 NULL 直加，否则 LEAST(cap, used+delta)，
--   并发父/子同时记账不超顶、不丢单。
ALTER TABLE agent_session
    ADD COLUMN max_aggregate_tokens bigint,
    ADD COLUMN aggregate_tokens_used bigint NOT NULL DEFAULT 0;

-- 聚合列不允许出现负数（应用层负值防御之外数据库 CHECK 兜底）
ALTER TABLE agent_session
    ADD CONSTRAINT ck_agent_session_aggregate_tokens_used CHECK (aggregate_tokens_used >= 0);
