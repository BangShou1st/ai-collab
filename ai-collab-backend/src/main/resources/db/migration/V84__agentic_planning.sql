-- V84: 任务规划 agentic 化（设计文档 agent-fanout-budget-and-agentic-planning.md §5）
-- 1) ai_task_plan.status 增加 SKELETON_REVIEW（§5.5 人工检查点，本期仅放开枚举、流转不启用）
--    与 REFLECTING（§5.3 反思阶段）。
--    当前约束定义来自 V8（V7 曾用错误约束名更新失败，V8 重建），此处按现状 DROP+ADD。
ALTER TABLE ai_task_plan DROP CONSTRAINT IF EXISTS ck_ai_task_plan_status;
ALTER TABLE ai_task_plan ADD CONSTRAINT ck_ai_task_plan_status
    CHECK (status IN (
        'SKELETON_GENERATING', 'SKELETON_REVIEW', 'DETAIL_GENERATING', 'REFLECTING',
        'REPAIRING', 'DETAIL_GENERATION_FAILED', 'READY', 'READY_WITH_ISSUES',
        'CONFIRMING', 'CONFIRMED', 'FAILED', 'CANCELED'
    ));

-- 2) 修复上限 1 → 2（§5.3：初次 + 修复1 + 修复2）
ALTER TABLE ai_task_plan_attempt DROP CONSTRAINT ck_ai_task_plan_attempt_repair;
ALTER TABLE ai_task_plan_attempt ADD CONSTRAINT ck_ai_task_plan_attempt_repair
    CHECK (repair_count BETWEEN 0 AND 2);

-- 3) attempt.stage 增加 REFLECT（反思阶段的独立 attempt 行，计量与审计粒度与生成一致）
ALTER TABLE ai_task_plan_attempt DROP CONSTRAINT ck_ai_task_plan_attempt_stage;
ALTER TABLE ai_task_plan_attempt ADD CONSTRAINT ck_ai_task_plan_attempt_stage
    CHECK (stage IN ('SKELETON', 'DETAIL', 'REPAIR', 'REFLECT'));
