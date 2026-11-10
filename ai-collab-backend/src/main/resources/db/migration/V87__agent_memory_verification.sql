-- V87 (AC07-P1 第一波): agent_memory 来源验证状态
-- 设计依据: docs/design/AI-Collab-AC-07-Agent-Memory-V2-设计补充-2026-11-10.md §5.2/§6.1
-- 红线: legacy 记录不得静默升级为 VERIFIED —— 存量与新行一律默认 UNVERIFIED,
--       仅允许管理员经 update/disable 路径显式置为 VERIFIED(记录 verified_at/verified_by)。
-- SOURCE_REVOKED 为系统派生状态(由既有 Connector 撤权过滤链路隔离), 不通过 API 写入。
ALTER TABLE agent_memory ADD COLUMN verification_state varchar(20) NOT NULL DEFAULT 'UNVERIFIED';
ALTER TABLE agent_memory ADD COLUMN verified_at timestamptz;
ALTER TABLE agent_memory ADD COLUMN verified_by uuid REFERENCES app_user(id);

ALTER TABLE agent_memory ADD CONSTRAINT ck_agent_memory_verification_state
    CHECK (verification_state IN ('VERIFIED','UNVERIFIED','STALE','CONFLICTED','SOURCE_REVOKED'));
