-- Parsed text is independent of embedding availability. Existing documents use compatible chunk reads.
CREATE TABLE document_body (
    document_id uuid PRIMARY KEY REFERENCES project_document(id) ON DELETE CASCADE,
    snapshot_id uuid NOT NULL UNIQUE,
    original_content_hash varchar(64) NOT NULL,
    parse_version varchar(80) NOT NULL,
    parsed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE document_body_chunk (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES document_body(document_id) ON DELETE CASCADE,
    chunk_no integer NOT NULL,
    heading text,
    content text NOT NULL,
    content_hash varchar(64) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}',
    UNIQUE(document_id,chunk_no)
);
