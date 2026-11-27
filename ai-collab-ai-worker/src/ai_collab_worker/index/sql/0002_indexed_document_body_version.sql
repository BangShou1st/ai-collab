ALTER TABLE ai_index.indexed_document
    ADD COLUMN body_version integer NOT NULL DEFAULT 0 CHECK (body_version >= 0);
