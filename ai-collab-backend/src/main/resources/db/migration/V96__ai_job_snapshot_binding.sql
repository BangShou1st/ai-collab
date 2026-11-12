-- V96: 冻结每个 Job attempt 的快照，并把结果绑定到该快照。
--
-- 候选 sourceRef 只能引用同一 Job/attempt 快照里的 facts[].ref 或
-- existingMemories[].id（memory:<id>）。快照在首次读取时落库，后续读取与审批都必须
-- 使用同一份快照，不能在项目/记忆变化后重新计算。

ALTER TABLE ai_job_attempt
    ADD COLUMN snapshot_hash VARCHAR(80),
    ADD COLUMN snapshot_refs JSONB,
    ADD COLUMN snapshot_payload TEXT,
    ADD CONSTRAINT ck_ai_job_attempt_snapshot_binding CHECK (
        (snapshot_hash IS NULL AND snapshot_refs IS NULL AND snapshot_payload IS NULL)
        OR (snapshot_hash IS NOT NULL AND snapshot_refs IS NOT NULL
            AND snapshot_payload IS NOT NULL AND jsonb_typeof(snapshot_refs) = 'array')
    );

ALTER TABLE ai_job_result
    ADD COLUMN snapshot_hash VARCHAR(80),
    ADD COLUMN snapshot_refs JSONB,
    ADD CONSTRAINT ck_ai_job_result_snapshot_binding CHECK (
        (snapshot_hash IS NULL AND snapshot_refs IS NULL)
        OR (snapshot_hash IS NOT NULL AND snapshot_refs IS NOT NULL
            AND jsonb_typeof(snapshot_refs) = 'array')
    );
