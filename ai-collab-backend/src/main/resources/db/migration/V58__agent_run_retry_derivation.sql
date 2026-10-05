-- 终态运行（FAILED/CANCELED/BUDGET_EXCEEDED）的"重新尝试"创建新运行并保留旧运行记录。
-- retried_from_run_id 记录派生来源；部分唯一索引保证同一旧运行至多派生一个新运行，
-- 并发/重复重试不会重复执行业务动作（第二个请求幂等返回既有派生运行）。
ALTER TABLE agent_run ADD COLUMN retried_from_run_id uuid REFERENCES agent_run(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX ux_agent_run_retried_from
    ON agent_run(retried_from_run_id)
    WHERE retried_from_run_id IS NOT NULL;
