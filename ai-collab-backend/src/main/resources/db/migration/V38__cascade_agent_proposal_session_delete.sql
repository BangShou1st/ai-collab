-- Agent 提案属于会话生命周期；删除会话时一并删除审批及其修订历史。
-- V37 创建这些外键时未声明级联，导致包含提案的会话无法删除。

ALTER TABLE agent_approval
  DROP CONSTRAINT fk_agent_approval_session,
  ADD CONSTRAINT fk_agent_approval_session
    FOREIGN KEY (session_id) REFERENCES agent_session(id) ON DELETE CASCADE;

ALTER TABLE agent_approval_revision
  DROP CONSTRAINT fk_approval_revision_approval,
  DROP CONSTRAINT fk_approval_revision_project,
  DROP CONSTRAINT fk_approval_revision_run,
  ADD CONSTRAINT fk_approval_revision_approval
    FOREIGN KEY (approval_id) REFERENCES agent_approval(id) ON DELETE CASCADE,
  ADD CONSTRAINT fk_approval_revision_project
    FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE,
  ADD CONSTRAINT fk_approval_revision_run
    FOREIGN KEY (source_run_id) REFERENCES agent_run(id) ON DELETE CASCADE;
