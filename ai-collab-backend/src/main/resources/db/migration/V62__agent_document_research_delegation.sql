-- V62: document_research 只读子 Agent 委派。
-- 复用既有 agent_run 父子结构（parent_run_id/role/depth，V22 已建）：主运行 depth=0/SUPERVISOR，
-- 委派子运行 depth=1/KNOWLEDGE_RESEARCHER，子运行预算从父运行剩余额度中切出，
-- 子运行终态经既有 resumeParent 链唤醒父运行（CREATED→QUEUED），不新增第二套编排或 checkpoint。
-- 委派身份记录在 agent_step（DELEGATION_REQUESTED / DELEGATION_COMPLETED，V22 已在类型约束内），
-- 事件类型沿用 V61 约束，无 schema 变更；占位迁移标记该能力对应本批生产代码。
SELECT 1;
