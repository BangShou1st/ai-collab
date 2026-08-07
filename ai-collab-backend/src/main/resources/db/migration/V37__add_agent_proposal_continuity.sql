-- Agent Proposal Continuity: 添加提案元数据、修订历史和 APPROVAL_UPDATED 事件
-- 用于支持跨轮次提案修订、最新需求覆盖和确定性即时回答

-- 1. 在 agent_approval 表添加提案连续性列
ALTER TABLE agent_approval
  ADD COLUMN session_id uuid,
  ADD COLUMN proposal_family varchar(40),
  ADD COLUMN subject_key uuid,
  ADD COLUMN revision integer NOT NULL DEFAULT 1,
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- 2. 回填 session_id 从关联的 agent_run
UPDATE agent_approval a
SET session_id = r.session_id
FROM agent_run r
WHERE a.run_id = r.id AND a.session_id IS NULL;

-- 3. 回填 proposal_family 根据 tool_name 映射
UPDATE agent_approval
SET proposal_family = CASE tool_name
  WHEN 'create_task_after_approval' THEN 'TASK_CREATE'
  WHEN 'update_task_after_approval' THEN 'TASK_UPDATE'
  WHEN 'create_milestone_after_approval' THEN 'MILESTONE_CREATE'
  WHEN 'update_milestone_after_approval' THEN 'MILESTONE_UPDATE'
  WHEN 'create_memory_after_approval' THEN 'MEMORY_CREATE'
  ELSE 'UNKNOWN'
END
WHERE proposal_family IS NULL;

-- 4. 回填 subject_key：更新操作使用 resource_id，创建操作使用 approval id
UPDATE agent_approval
SET subject_key = COALESCE(resource_id, id)
WHERE subject_key IS NULL;

-- 5. 设置 NOT NULL 约束（回填完成后）
ALTER TABLE agent_approval
  ALTER COLUMN session_id SET NOT NULL,
  ALTER COLUMN proposal_family SET NOT NULL,
  ALTER COLUMN subject_key SET NOT NULL;

-- 6. 添加外键约束
ALTER TABLE agent_approval
  ADD CONSTRAINT fk_agent_approval_session
  FOREIGN KEY (session_id) REFERENCES agent_session(id);

-- 7. 添加索引用于查询优化
CREATE INDEX idx_agent_approval_session_family
  ON agent_approval(session_id, proposal_family, status);

CREATE INDEX idx_agent_approval_subject
  ON agent_approval(subject_key, proposal_family, status);

-- 8. 创建不可变修订历史表
CREATE TABLE agent_approval_revision (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id uuid NOT NULL,
  approval_id uuid NOT NULL,
  source_run_id uuid NOT NULL,
  revision integer NOT NULL,
  before_arguments_json jsonb NOT NULL,
  after_arguments_json jsonb NOT NULL,
  diff_json jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),

  CONSTRAINT fk_approval_revision_approval
    FOREIGN KEY (approval_id) REFERENCES agent_approval(id),
  CONSTRAINT fk_approval_revision_project
    FOREIGN KEY (project_id) REFERENCES project(id),
  CONSTRAINT fk_approval_revision_run
    FOREIGN KEY (source_run_id) REFERENCES agent_run(id),
  CONSTRAINT uq_approval_revision UNIQUE (approval_id, revision)
);

-- 9. 重建 agent_run_event 的 type 约束，增加 APPROVAL_UPDATED
ALTER TABLE agent_run_event DROP CONSTRAINT ck_agent_run_event_type;
ALTER TABLE agent_run_event ADD CONSTRAINT ck_agent_run_event_type CHECK (type IN (
    'RUN_CREATED','CONTEXT_CAPTURED','SKILL_SELECTED',
    'PLAN_CREATED','PLAN_UPDATED','MODEL_STARTED','MODEL_COMPLETED',
    'TOOL_CALL_PROPOSED','TOOL_CALL_STARTED','TOOL_CALL_COMPLETED','TOOL_CALL_FAILED',
    'APPROVAL_REQUESTED','APPROVAL_APPROVED','APPROVAL_REJECTED',
    'APPROVAL_EXPIRED','APPROVAL_UPDATED',
    'RESULT_VERIFIED','RUN_RETRY_SCHEDULED',
    'RUN_CANCELED','RUN_SUCCEEDED','RUN_FAILED','RUN_BUDGET_EXCEEDED',
    'WAITING_FOR_USER_INPUT'
));
