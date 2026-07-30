-- 站内通知表
CREATE TABLE notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id),
    type varchar(50) NOT NULL,
    title varchar(200) NOT NULL,
    content text,
    entity_type varchar(50),
    entity_id uuid,
    is_read boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_type CHECK (type IN (
        'TASK_ASSIGNED', 'TASK_STATUS_CHANGED', 'TASK_DUE_SOON', 'TASK_OVERDUE',
        'DEPENDENCY_COMPLETED', 'DOCUMENT_PROCESSED', 'DOCUMENT_FAILED',
        'PLAN_CONFIRMED', 'COMMENT_ADDED', 'MILESTONE_COMPLETED'
    ))
);
CREATE INDEX idx_notification_user ON notification(user_id, is_read, created_at DESC);
CREATE INDEX idx_notification_project ON notification(project_id, created_at DESC);
