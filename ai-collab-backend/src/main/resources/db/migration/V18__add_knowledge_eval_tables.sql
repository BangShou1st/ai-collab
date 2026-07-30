-- 知识库检索质量评测表
CREATE TABLE knowledge_eval_run (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    triggered_by uuid NOT NULL REFERENCES app_user(id),
    status varchar(20) NOT NULL DEFAULT 'RUNNING',
    total_questions integer NOT NULL DEFAULT 0,
    recall_at_3 numeric(6,4),
    recall_at_5 numeric(6,4),
    mrr numeric(6,4),
    avg_similarity numeric(6,4),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_eval_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);
CREATE INDEX idx_eval_run_project ON knowledge_eval_run(project_id, created_at DESC);

CREATE TABLE knowledge_eval_result (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id uuid NOT NULL REFERENCES knowledge_eval_run(id) ON DELETE CASCADE,
    question varchar(500) NOT NULL,
    expected_document_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    retrieved_document_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    recall_at_3 numeric(6,4),
    recall_at_5 numeric(6,4),
    mrr numeric(6,4),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_eval_result_run ON knowledge_eval_result(run_id);
