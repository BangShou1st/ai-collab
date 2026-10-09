ALTER TABLE system_embedding_config DROP CONSTRAINT ck_system_embedding_provider;
ALTER TABLE system_embedding_config ADD CONSTRAINT ck_system_embedding_provider
    CHECK (provider IN ('OPENAI_COMPATIBLE','ANTHROPIC','GEMINI','OLLAMA'));

CREATE TABLE embedding_index_generation (
    id UUID PRIMARY KEY,
    config_id UUID NOT NULL REFERENCES system_embedding_config(id),
    base_config_id UUID,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED','BUILDING','READY','ACTIVE','RETIRED','FAILED')),
    total INTEGER NOT NULL DEFAULT 0,
    completed INTEGER NOT NULL DEFAULT 0,
    failed INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_embedding_pending_build ON embedding_index_generation ((true))
    WHERE state IN ('QUEUED','BUILDING','READY');
ALTER TABLE system_embedding_config ADD COLUMN generation_id UUID REFERENCES embedding_index_generation(id);
ALTER TABLE document_chunk ADD COLUMN generation_id UUID REFERENCES embedding_index_generation(id);
ALTER TABLE document_chunk DROP CONSTRAINT document_chunk_document_id_chunk_no_key;
CREATE UNIQUE INDEX uk_document_chunk_generation ON document_chunk(document_id,chunk_no,generation_id) NULLS NOT DISTINCT;
CREATE INDEX idx_document_chunk_generation ON document_chunk(generation_id);
CREATE TABLE embedding_index_document (
    generation_id UUID NOT NULL REFERENCES embedding_index_generation(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES project_document(id) ON DELETE CASCADE,
    source_version INTEGER NOT NULL,
    source_indexed_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL CHECK (state IN ('QUEUED','BUILDING','COMPLETED','FAILED')),
    PRIMARY KEY(generation_id,document_id)
);
