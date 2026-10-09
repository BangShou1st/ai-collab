-- 实际用量如实累计：input_tokens_used / output_tokens_used 保持预算语义
-- （V22 的 ck_agent_run_budgets 要求 used <= max，继续由 LEAST 封顶），
-- 新增 *_actual 列记录提供商上报或按证据估算的真实消耗，允许高于预算上限。
-- 预算剩余量按 used 计算（最低为 0）；actual 仅用于审计与超额判定。
-- 历史行用 used 回填 actual：used 是被截断后保留的值，作为下界不虚报真实消耗。
ALTER TABLE agent_run ADD COLUMN input_tokens_actual BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN output_tokens_actual BIGINT NOT NULL DEFAULT 0;

UPDATE agent_run
SET input_tokens_actual = input_tokens_used,
    output_tokens_actual = output_tokens_used;

ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_actual_tokens
    CHECK (input_tokens_actual >= 0 AND output_tokens_actual >= 0);

-- usage 来源区分：PROVIDER=提供商上报；ESTIMATED=按可得证据估算；UNKNOWN=无任何证据。
-- 旧行不可考，统一标 UNKNOWN，不把历史估算冒充上报值。
ALTER TABLE agent_step ADD COLUMN usage_basis VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE agent_step ADD CONSTRAINT ck_agent_step_usage_basis
    CHECK (usage_basis IN ('PROVIDER', 'ESTIMATED', 'UNKNOWN'));
