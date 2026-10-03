ALTER TABLE embedding_index_generation ADD COLUMN base_fingerprint VARCHAR(64);
ALTER TABLE embedding_index_generation ADD COLUMN base_generation_id UUID;
