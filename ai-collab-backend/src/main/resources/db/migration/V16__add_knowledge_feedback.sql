-- 知识问答回答反馈表：记录用户对回答的有用/无用评价
CREATE TABLE knowledge_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL REFERENCES knowledge_message(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    helpful boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(message_id, user_id)
);
CREATE INDEX idx_knowledge_feedback_message ON knowledge_feedback(message_id);
