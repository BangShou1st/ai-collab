-- V106: 引用身份改为 documentId + chunkNo + contentHash（契约 docs/design/p2-rag-contract-2026-11-27.md §5.4、§6）。
--
-- * 删除 knowledge_citation 指向 document_chunk(id) 的外键；chunk_id 改为可空，只留给历史行与回滚期旧代码；
-- * 新增 document_id（级联删除）、chunk_no、content_hash、body_snapshot_id；
-- * 历史行从 document_chunk 回填 document_id / chunk_no / content_hash。原外键带 ON DELETE CASCADE，
--   因此每一条历史引用都能回填到；回填后 document_id 置 NOT NULL（若有孤儿行，迁移会失败而不是静默删数据）。
--   body_snapshot_id 无法从 document_chunk 得到，历史行保持 NULL；
-- * 新行要么带 chunk_id（旧代码 / 回滚），要么带 chunk_no + content_hash（P2 之后）。
-- 只增不改：不修改历史迁移，document_chunk 保留到 P7。

DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'knowledge_citation'::regclass
          AND contype = 'f'
          AND confrelid = 'document_chunk'::regclass
    LOOP
        EXECUTE format('ALTER TABLE knowledge_citation DROP CONSTRAINT %I', fk.conname);
    END LOOP;
END $$;

ALTER TABLE knowledge_citation ALTER COLUMN chunk_id DROP NOT NULL;

ALTER TABLE knowledge_citation
    ADD COLUMN document_id      uuid REFERENCES project_document(id) ON DELETE CASCADE,
    ADD COLUMN chunk_no         integer,
    ADD COLUMN content_hash     char(64),
    ADD COLUMN body_snapshot_id uuid;

UPDATE knowledge_citation kc
SET document_id  = dc.document_id,
    chunk_no     = dc.chunk_no,
    content_hash = dc.content_hash
FROM document_chunk dc
WHERE dc.id = kc.chunk_id;

ALTER TABLE knowledge_citation ALTER COLUMN document_id SET NOT NULL;

ALTER TABLE knowledge_citation
    ADD CONSTRAINT ck_knowledge_citation_identity CHECK (
        chunk_id IS NOT NULL OR (chunk_no IS NOT NULL AND content_hash IS NOT NULL));

CREATE INDEX idx_knowledge_citation_document_chunk ON knowledge_citation (document_id, chunk_no);

COMMENT ON COLUMN knowledge_citation.chunk_id IS '历史引用的 document_chunk.id；P2 之后的新引用为 NULL，不再有外键';
COMMENT ON COLUMN knowledge_citation.body_snapshot_id IS '引用时的 document_body.snapshot_id；历史行为 NULL';
