-- V80__rag_v2_sentence_candidate_indexing.sql
-- 支持句子级候选检索 (Sentence Window Retrieval) 与真实分块类型扩展

ALTER TABLE document_chunk
    DROP CONSTRAINT IF EXISTS ck_document_chunk_type;

ALTER TABLE document_chunk
    ADD CONSTRAINT ck_document_chunk_type
    CHECK (chunk_type IN ('STANDARD', 'PARENT', 'CHILD', 'SENTENCE'));

CREATE INDEX IF NOT EXISTS idx_document_chunk_sentence_parent
    ON document_chunk(project_id, parent_chunk_id, chunk_type);
