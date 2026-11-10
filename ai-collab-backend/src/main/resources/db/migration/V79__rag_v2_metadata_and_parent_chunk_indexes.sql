-- V79__rag_v2_metadata_and_parent_chunk_indexes.sql
-- 支持 RAG V2 元数据硬过滤下推与真实父子分块检索索引

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 为 document_chunk 的 metadata 建立 GIN 索引，加速 JSONB tags 与属性检索
CREATE INDEX IF NOT EXISTS idx_document_chunk_metadata_gin
    ON document_chunk USING gin (metadata);

-- 为 project_document 的 original_filename 建立 gin_trgm 索引，加速文件名模糊匹配
CREATE INDEX IF NOT EXISTS idx_project_document_filename_trgm
    ON project_document USING gin (original_filename gin_trgm_ops);

-- 为 document_chunk 的 chunk_type 建立索引，加速初筛排除父分块
CREATE INDEX IF NOT EXISTS idx_document_chunk_chunk_type
    ON document_chunk (chunk_type);
