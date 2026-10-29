-- Add user grants without changing existing application-identity connections.
CREATE TABLE connector_oauth_authorization (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES connector_connection(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id),
    status VARCHAR(24) NOT NULL,
    access_token_cipher TEXT,
    refresh_token_cipher TEXT,
    access_expires_at TIMESTAMPTZ,
    refresh_expires_at TIMESTAMPTZ,
    granted_scope TEXT NOT NULL DEFAULT '',
    feishu_subject_hash CHAR(64),
    token_issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    token_version BIGINT NOT NULL DEFAULT 0,
    refresh_claim UUID,
    refresh_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_connector_oauth_status CHECK (status IN
        ('ACTIVE', 'REFRESHING', 'REFRESH_UNCERTAIN', 'REAUTH_REQUIRED', 'REVOKED')),
    CONSTRAINT ck_connector_oauth_active_material CHECK (status NOT IN ('ACTIVE','REFRESHING') OR
        (access_token_cipher IS NOT NULL AND refresh_token_cipher IS NOT NULL AND access_expires_at IS NOT NULL
         AND refresh_expires_at IS NOT NULL AND feishu_subject_hash IS NOT NULL)),
    CONSTRAINT ck_connector_oauth_refresh_claim CHECK (
        (status='REFRESHING' AND refresh_claim IS NOT NULL AND refresh_started_at IS NOT NULL) OR
        (status<>'REFRESHING' AND refresh_claim IS NULL AND refresh_started_at IS NULL)),
    CONSTRAINT uk_connector_oauth_project_user UNIQUE (project_id, connection_id, user_id),
    CONSTRAINT uk_connector_oauth_project_binding UNIQUE (id, project_id, connection_id)
);

-- Event credentials are write-only and encrypted with the existing server-side master key.
CREATE TABLE connector_event_credential (
    connection_id UUID PRIMARY KEY REFERENCES connector_connection(id) ON DELETE CASCADE,
    encrypt_key_cipher TEXT NOT NULL,
    verification_token_cipher TEXT NOT NULL,
    verified_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE connector_oauth_revocation_marker (
    connection_id UUID NOT NULL REFERENCES connector_connection(id) ON DELETE CASCADE,
    subject_hash CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (connection_id,subject_hash)
);
CREATE TABLE connector_oauth_event_receipt (
    connection_id UUID NOT NULL REFERENCES connector_connection(id) ON DELETE CASCADE,
    event_id VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (connection_id,event_id)
);

CREATE TABLE connector_oauth_flow (
    state_hash VARCHAR(64) PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES connector_connection(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id),
    verifier_cipher TEXT NOT NULL,
    redirect_uri TEXT NOT NULL,
    grant_version BIGINT,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE connector_source ADD COLUMN user_authorization_id UUID;
ALTER TABLE connector_source ADD CONSTRAINT fk_connector_source_user_authorization
    FOREIGN KEY (user_authorization_id, project_id, connection_id)
    REFERENCES connector_oauth_authorization(id, project_id, connection_id);

-- Blocking is independent of deletion, so temporary or expired grants do not destroy data.
ALTER TABLE project_document ADD COLUMN access_blocked BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve enough information to retry deletion and replay a tombstone after backup restore.
CREATE TABLE connector_deletion_journal (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    source_id UUID NOT NULL,
    document_id UUID NOT NULL,
    object_key TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(32) NOT NULL,
    CONSTRAINT uk_connector_deletion_journal UNIQUE (project_id, source_id, document_id)
);

CREATE TABLE connector_cleanup_job (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL UNIQUE REFERENCES connector_deletion_journal(id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_id UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_connector_cleanup_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETE'))
);

-- Historical answers may lack citations, so conservatively suppress their content on blocking.
CREATE TABLE connector_hidden_answer (
    project_id UUID NOT NULL,
    message_id UUID NOT NULL,
    source_id UUID NOT NULL,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cleared_at TIMESTAMPTZ,
    PRIMARY KEY (project_id, message_id, source_id)
);

-- Agent sessions can contain uncited tool results and summaries; quarantine the session as a whole.
CREATE TABLE connector_hidden_agent_session (
    project_id UUID NOT NULL,
    session_id UUID NOT NULL,
    source_id UUID NOT NULL,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cleared_at TIMESTAMPTZ,
    PRIMARY KEY (project_id, session_id, source_id)
);
CREATE TABLE connector_hidden_agent_memory (
    project_id UUID NOT NULL,
    memory_id UUID NOT NULL,
    source_id UUID NOT NULL,
    hidden_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cleared_at TIMESTAMPTZ,
    PRIMARY KEY (project_id, memory_id, source_id)
);

-- Shared, fail-closed projection for document metadata exposed outside the document module.
CREATE VIEW connector_document_visibility AS
SELECT d.id,d.project_id,
       (d.status <> 'DELETING' AND NOT d.access_blocked AND NOT EXISTS (
           SELECT 1 FROM external_document e
           JOIN connector_source s ON s.id=e.source_id AND s.project_id=d.project_id
           LEFT JOIN connector_oauth_authorization a ON a.id=s.user_authorization_id AND a.project_id=s.project_id
           WHERE e.document_id=d.id AND (e.status<>'ACTIVE' OR s.status<>'ACTIVE'
             OR (s.user_authorization_id IS NOT NULL AND
               (a.id IS NULL OR a.status<>'ACTIVE' OR a.access_expires_at<=now())))
       )) AS readable
FROM project_document d;

CREATE INDEX idx_connector_cleanup_due ON connector_cleanup_job (status, next_attempt_at);
CREATE INDEX idx_connector_source_oauth ON connector_source (user_authorization_id);
CREATE INDEX idx_connector_oauth_subject ON connector_oauth_authorization (connection_id,feishu_subject_hash);
