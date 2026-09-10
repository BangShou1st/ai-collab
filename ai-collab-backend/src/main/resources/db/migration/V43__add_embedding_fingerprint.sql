-- V43: embedding fingerprint columns plus legacy system-embedding seeding.
-- fingerprint = sha256(provider|model|dimensions); documents/chunks store the space they were
-- built in so mixed spaces are never searched together.
-- Case A (no legacy configs): system stays unconfigured.
-- Case B (one agreed semantic incl. endpoint): promoted WITHOUT api key, disabled until admin acts.
-- Case C (conflicting semantics): system stays unconfigured, existing vectors stay readable as
-- legacy until admin configures and reindexes; migration never fails on normal multi-project data.

ALTER TABLE project_document ADD COLUMN embedding_fingerprint VARCHAR(128);
ALTER TABLE document_chunk ADD COLUMN embedding_fingerprint VARCHAR(128);

UPDATE project_document
SET embedding_fingerprint = encode(digest(
        embedding_provider || '|' || embedding_model || '|' || embedding_dimension::text, 'sha256'), 'hex')
WHERE embedding_provider IS NOT NULL
  AND embedding_model IS NOT NULL
  AND embedding_dimension IS NOT NULL;

UPDATE document_chunk
SET embedding_fingerprint = encode(digest(
        embedding_provider || '|' || embedding_model || '|' || embedding_dimension::text, 'sha256'), 'hex');

WITH semantic AS (
    SELECT provider, base_url, api_path, model_name, dimensions, min(batch_size) AS batch_size
    FROM project_embedding_config
    GROUP BY provider, base_url, api_path, model_name, dimensions
),
agreed AS (
    SELECT * FROM semantic
    WHERE (SELECT count(*) FROM semantic) = 1
      AND (SELECT count(*) FROM project_embedding_config) > 0
)
INSERT INTO system_embedding_config (
    id, provider, base_url, api_path, encrypted_api_key, model_name, dimensions,
    batch_size, fingerprint, enabled
)
SELECT gen_random_uuid(), agreed.provider, agreed.base_url, agreed.api_path, NULL,
       agreed.model_name, agreed.dimensions, agreed.batch_size,
       encode(digest(agreed.provider || '|' || agreed.model_name || '|' || agreed.dimensions::text,
              'sha256'), 'hex'),
       FALSE
FROM agreed;
