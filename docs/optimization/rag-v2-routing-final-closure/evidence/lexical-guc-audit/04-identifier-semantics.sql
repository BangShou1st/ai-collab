SET pg_trgm.similarity_threshold = 0.03;
SELECT 'thr_0.03' AS threshold, count(*) FROM document_chunk c WHERE c.content % 'JWT token';
SET pg_trgm.similarity_threshold = 0.3;
SELECT 'thr_0.3' AS threshold, count(*) FROM document_chunk c WHERE c.content % 'JWT token';
