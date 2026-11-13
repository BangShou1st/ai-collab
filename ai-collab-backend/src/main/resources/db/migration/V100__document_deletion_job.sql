CREATE TABLE document_deletion_job (
    id              uuid PRIMARY KEY,
    project_id      uuid NOT NULL,
    document_id     uuid NOT NULL,
    object_key      text NOT NULL,
    status          varchar(16) NOT NULL
                    CHECK (status IN ('PENDING','RUNNING','DONE','FAILED')),
    attempts        integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until     timestamptz,
    last_error      varchar(500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_deletion_job_document UNIQUE (document_id)
);

CREATE INDEX ix_document_deletion_job_due
    ON document_deletion_job (status, next_attempt_at);
