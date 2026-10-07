-- V64: 上下文容量策略版本（256k 软压缩触发 + 累计 token 只统计不限额）。
--
-- context_policy_version 是"资源策略版本"的最小兼容标记，与推进计数语义
-- （budget_semantics，V63）是两个不同维度：
--   v1 = 既有行为（既有行默认）：累计 max_input_tokens / max_output_tokens 参与
--        准入、收敛、委派切分与停机；恢复与暂停续跑保持原额度含义，不重解释。
--   v2 = 新根运行与终态重试派生：单次请求守当前模型真实窗口与本次最大输出，
--        累计输入/输出只统计真实/估算用量，不设累计上限、不参与准入/收敛/委派拒绝；
--        父子各自独立的有限执行额度（子不从父剩余切分）。
--
-- v2 用 NULL 明确表达"无累计上限"：不用 0、8M、Integer.MAX_VALUE 或任何假额度冒充
-- "关闭限制"。v1 行保留原值与原约束语义，不批量重解释旧数据。
-- 模型配置的单次最大输出（provider 配置 / 用户设置）不受本迁移影响，仍保持有效值。

ALTER TABLE agent_run
    ADD COLUMN context_policy_version smallint NOT NULL DEFAULT 1;

ALTER TABLE agent_run
    ADD CONSTRAINT ck_agent_run_context_policy_version
    CHECK (context_policy_version IN (1, 2));

-- 旧运行（v1）语义不变：既有行的 max_* 保持原值。
-- 新 v2 行由创建路径显式写 NULL。
ALTER TABLE agent_run ALTER COLUMN max_input_tokens DROP NOT NULL;
ALTER TABLE agent_run ALTER COLUMN max_output_tokens DROP NOT NULL;

-- 重建预算约束，区分"旧限额行"与"新无限额行"：
-- v1 行仍要求 used <= max（LEAST 封顶语义不变）；
-- v2 行 max 为 NULL 时只要求非负计数，不引入任何替代额度。
ALTER TABLE agent_run DROP CONSTRAINT ck_agent_run_budgets;
ALTER TABLE agent_run ADD CONSTRAINT ck_agent_run_budgets CHECK (
    max_steps BETWEEN 1 AND 100
    AND max_tool_calls BETWEEN 0 AND 100
    AND max_children BETWEEN 0 AND 3
    AND (max_input_tokens IS NULL OR max_input_tokens BETWEEN 1 AND 2000000)
    AND (max_output_tokens IS NULL OR max_output_tokens BETWEEN 1 AND 500000)
    AND steps_used BETWEEN 0 AND max_steps
    AND tool_calls_used BETWEEN 0 AND max_tool_calls
    AND children_used BETWEEN 0 AND max_children
    AND input_tokens_used >= 0
    AND (max_input_tokens IS NULL OR input_tokens_used <= max_input_tokens)
    AND output_tokens_used >= 0
    AND (max_output_tokens IS NULL OR output_tokens_used <= max_output_tokens)
    AND estimated_cost >= 0
    AND retry_count BETWEEN 0 AND 10
    AND version >= 0
);

CREATE INDEX idx_agent_run_context_policy ON agent_run(context_policy_version);

-- 累计计数推进的唯一算术入口：cap 存在时按 cap 封顶（v1 既有预算语义不变），
-- cap 为 NULL（v2 无累计上限）时不封顶、只如实累计。
-- 必须显式处理 NULL：SQL 的 LEAST(NULL, x) 会返回 NULL，会把 NOT NULL 计数写成 NULL。
-- 这里不使用 8M/Integer.MAX_VALUE 之类的"假额度"替代 NULL——NULL 就是"无上限"本身，
-- business 层读到的上限仍然是 NULL，不用任何数字冒充。
CREATE FUNCTION agent_capped_add(used integer, cap integer, delta integer)
RETURNS integer
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE WHEN cap IS NULL THEN used + delta
                ELSE LEAST(cap, used + delta)
           END
$$;

-- bigint 版本（actual 列是 bigint）
CREATE FUNCTION agent_capped_add(used bigint, cap bigint, delta bigint)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE WHEN cap IS NULL THEN used + delta
                ELSE LEAST(cap, used + delta)
           END
$$;
