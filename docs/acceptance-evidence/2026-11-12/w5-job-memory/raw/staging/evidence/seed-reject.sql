INSERT INTO ai_job_result (job_id, attempt_no, result_kind, dedupe_key, payload, review_status)
VALUES ('3b2efcb5-1906-4e41-a2d6-8c1768ad751b', 1, 'MEMORY_CANDIDATE', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
        '{"kind":"DECISION","title":"Reject E2E 候选","content":"该候选仅用于验证拒绝路径。","confidence":0.8,"sourceRef":"fact:project","dedupeKey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}'::jsonb,
        'PENDING_REVIEW')
RETURNING id, job_id, review_status;
