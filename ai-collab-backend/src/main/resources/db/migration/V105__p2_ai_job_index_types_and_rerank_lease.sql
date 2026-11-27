-- V105: P2 RAG 迁到 Python（契约 docs/design/p2-rag-contract-2026-11-27.md §4.2、§5.4、§12 P1-R4）。
-- 只触及 public schema；不读写 ai_index / ai_runtime。
--
-- 1) ai_job 增加三种索引任务类型：INDEX_DOCUMENT、DELETE_DOCUMENT_INDEX、REINDEX_GENERATION。
-- 2) ai_job 增加 document_id / generation_id / parent_job_id。V91 只有无类型的 payload JSONB，
--    无法建索引、无法做父子级联，也无法用 CHECK 兜底，因此新增强类型列，不复用 payload。
--    document_id 不加指向 project_document 的外键：DELETE_DOCUMENT_INDEX 必须在文档行删除之后仍然存在。
--    generation_id 是 ai_index.index_generation 的 id，跨 schema 不建外键（Java 不镜像 generation 表，Q5）。
-- 3) ai_credential_lease 的用途增加 RERANK（P1-R4）。
-- 4) （P2-R4 R4-1）REINDEX_GENERATION 是全站任务，不属于任何项目：project_id 改为可空，
--    并用 CHECK 限定只有 REINDEX_GENERATION 可以没有项目，其余类型（含 MEMORY_EXTRACTION）仍必须有。
--    project_id 外键（V97，ON DELETE CASCADE）保持不变。

ALTER TABLE ai_job DROP CONSTRAINT ck_ai_job_type;
ALTER TABLE ai_job
    ADD CONSTRAINT ck_ai_job_type CHECK (job_type IN
        ('MEMORY_EXTRACTION', 'INDEX_DOCUMENT', 'DELETE_DOCUMENT_INDEX', 'REINDEX_GENERATION'));

ALTER TABLE ai_job
    ADD COLUMN document_id   uuid,
    ADD COLUMN generation_id uuid,
    ADD COLUMN parent_job_id uuid REFERENCES ai_job(id) ON DELETE CASCADE;

-- 文档级索引任务必须指明文档；子任务不能指向自己。
ALTER TABLE ai_job
    ADD CONSTRAINT ck_ai_job_document_bound CHECK (
        job_type NOT IN ('INDEX_DOCUMENT', 'DELETE_DOCUMENT_INDEX') OR document_id IS NOT NULL),
    ADD CONSTRAINT ck_ai_job_parent_not_self CHECK (parent_job_id IS NULL OR parent_job_id <> id);

CREATE INDEX idx_ai_job_parent_status ON ai_job (parent_job_id, status);

-- 只有全站重建任务可以不属于项目（P2-R4 R4-1）。
ALTER TABLE ai_job ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE ai_job
    ADD CONSTRAINT ck_ai_job_project_bound CHECK (
        project_id IS NOT NULL OR job_type = 'REINDEX_GENERATION');

COMMENT ON COLUMN ai_job.document_id IS 'INDEX_DOCUMENT / DELETE_DOCUMENT_INDEX 的目标文档；不建外键，删除任务需在文档删除后存活';
COMMENT ON COLUMN ai_job.generation_id IS 'ai_index.index_generation.id；重建扇出的子任务与 REINDEX_GENERATION 结果使用，跨 schema 不建外键';
COMMENT ON COLUMN ai_job.parent_job_id IS '重建扇出时指向 REINDEX_GENERATION 父任务';

ALTER TABLE ai_credential_lease DROP CONSTRAINT ck_ai_credential_lease_purpose;
ALTER TABLE ai_credential_lease
    ADD CONSTRAINT ck_ai_credential_lease_purpose CHECK (purpose IN
        ('KNOWLEDGE_CHAT', 'AGENT', 'PLANNING', 'QUERY_PLAN', 'MEMORY_EXTRACTION', 'EMBEDDING', 'RERANK'));
