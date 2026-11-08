\set Q '上线发布门禁'
SET pg_trgm.similarity_threshold = 0.3;
\timing on
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)
SELECT c.id, d.original_filename AS "documentFilename", c.heading, c.content, c.content_hash AS "contentHash",
       GREATEST(
           COALESCE(similarity(c.content, :'Q'), 0),
           CASE WHEN c.heading IS NOT NULL AND c.heading ILIKE ('%' || :'Q' || '%') THEN 0.8 ELSE 0 END,
           CASE WHEN c.content ILIKE ('%' || :'Q' || '%') THEN 0.6 ELSE 0 END
       ) AS similarity,
       c.metadata
FROM document_chunk c
JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
WHERE c.project_id='a1277b36-2587-438e-8aee-2e371eece911' AND d.status='READY' AND NOT d.access_blocked
  AND NOT EXISTS (SELECT 1 FROM external_document e
    JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
    LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
    WHERE e.document_id=d.id AND (e.status<>'ACTIVE' OR s.status<>'ACTIVE'
      OR (s.user_authorization_id IS NOT NULL AND (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now()))))
  AND c.generation_id IS NOT DISTINCT FROM '325b56cd-08c1-450b-bd99-06054bf49fd7'
  AND (c.chunk_type IS NULL OR c.chunk_type <> 'PARENT')
  AND (
      c.content % :'Q'
      OR c.content ILIKE ('%' || :'Q' || '%')
      OR (c.heading IS NOT NULL AND (c.heading % :'Q' OR c.heading ILIKE ('%' || :'Q' || '%')))
  )
ORDER BY similarity DESC, c.id ASC
LIMIT 20;
