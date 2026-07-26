ALTER TABLE project_document
    ADD COLUMN processing_token uuid,
    ADD COLUMN processing_heartbeat_at timestamptz;

UPDATE project_document
SET status = 'FAILED',
    error_message = '服务升级中断了文档处理，请重试',
    updated_at = now()
WHERE status IN ('PARSING', 'INDEXING');
