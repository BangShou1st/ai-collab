CREATE TABLE document_reindex_batch (
    project_id UUID PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    total INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE document_reindex_batch_item (
    project_id UUID NOT NULL REFERENCES document_reindex_batch(project_id) ON DELETE CASCADE,
    document_id UUID NOT NULL,
    source_version INTEGER NOT NULL,
    PRIMARY KEY(project_id,document_id)
);
