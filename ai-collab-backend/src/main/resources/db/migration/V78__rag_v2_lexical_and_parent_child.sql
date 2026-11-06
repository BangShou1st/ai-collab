-- V78__rag_v2_lexical_and_parent_child.sql
-- 支持 RAG V2 词项检索 (pg_trgm) 与父子分块结构，全量向下兼容历史数据

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 为 document_chunk 增加父子块关系与分块类型字段
ALTER TABLE document_chunk
    ADD COLUMN IF NOT EXISTS parent_chunk_id uuid REFERENCES document_chunk(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS chunk_type varchar(20) NOT NULL DEFAULT 'STANDARD';

-- 约束 chunk_type 有效取值
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_document_chunk_type'
    ) THEN
        ALTER TABLE document_chunk
            ADD CONSTRAINT ck_document_chunk_type
            CHECK (chunk_type IN ('STANDARD', 'PARENT', 'CHILD'));
    END IF;
END $$;

-- 为父子关联创建索引
CREATE INDEX IF NOT EXISTS idx_document_chunk_parent
    ON document_chunk(project_id, parent_chunk_id);

-- 为中文词项/n-gram 检索创建 GIN 索引
CREATE INDEX IF NOT EXISTS idx_document_chunk_content_trgm
    ON document_chunk USING gin (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_document_chunk_heading_trgm
    ON document_chunk USING gin (heading gin_trgm_ops);
