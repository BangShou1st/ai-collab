-- V88 (AC07-P1 第二波): agent_memory 修订历史与 SUPERSEDE 替代关系
-- 设计依据: docs/design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md §5.3/§6.2/§6.3
-- 减法原则: 只新增一张 agent_memory_revision 审计表与 agent_memory.superseded_by 关系列,
--           不建 candidate 表、不做 subject_key、不做 Dreaming(后续波次)。
-- 修订历史只保存操作前后状态快照(含原文 content,该列本身即业务正文),不复制其余敏感字段。

CREATE TABLE agent_memory_revision (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    memory_id uuid NOT NULL REFERENCES agent_memory(id) ON DELETE CASCADE,
    operation varchar(16) NOT NULL,
    before_content varchar(2000) NOT NULL,
    after_content varchar(2000) NOT NULL,
    before_state varchar(16) NOT NULL,
    after_state varchar(16) NOT NULL,
    verification_state_before varchar(20) NOT NULL,
    verification_state_after varchar(20) NOT NULL,
    superseded_by_target_id uuid REFERENCES agent_memory(id) ON DELETE SET NULL,
    reason varchar(500),
    actor_id uuid NOT NULL REFERENCES app_user(id),
    prev_version integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_agent_memory_revision_operation
        CHECK (operation IN ('REVISE','SUPERSEDE','DISABLE')),
    CONSTRAINT ck_agent_memory_revision_prev_version CHECK (prev_version >= 0)
);
CREATE INDEX idx_agent_memory_revision_memory ON agent_memory_revision(memory_id,created_at DESC);

ALTER TABLE agent_memory ADD COLUMN superseded_by uuid REFERENCES agent_memory(id);
-- 召回过滤按 superseded_by IS NULL 走既有索引,部分索引只服务反向追溯(替代了哪些记忆)。
CREATE INDEX idx_agent_memory_superseded_by ON agent_memory(superseded_by) WHERE superseded_by IS NOT NULL;
