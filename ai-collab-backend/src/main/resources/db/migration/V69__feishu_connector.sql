-- V69: Feishu knowledge-base connector (P4).
-- External knowledge sources reference existing project_document rows; the
-- indexing pipeline is reused, so connector tables only track provenance,
-- authorization evidence and sync state.

CREATE TABLE connector_connection (
    id UUID PRIMARY KEY,
    provider VARCHAR(30) NOT NULL DEFAULT 'feishu',
    name VARCHAR(100) NOT NULL,
    app_id VARCHAR(120) NOT NULL,
    app_secret_cipher TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    last_error TEXT,
    last_verified_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_connector_connection_provider_app UNIQUE (provider, app_id),
    CONSTRAINT ck_connector_connection_status CHECK (status IN ('ACTIVE', 'INVALID'))
);

CREATE TABLE connector_source (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    connection_id UUID NOT NULL REFERENCES connector_connection(id),
    folder_token VARCHAR(120) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    authorization_declared_by UUID NOT NULL REFERENCES app_user(id),
    authorization_declared_at TIMESTAMPTZ NOT NULL,
    last_verified_at TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_connector_source_project_folder UNIQUE (project_id, folder_token),
    CONSTRAINT ck_connector_source_status CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

-- document_id intentionally has no FK: on revocation the project_document row
-- is deleted immediately while this record is kept as audit evidence.
CREATE TABLE external_document (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES connector_source(id) ON DELETE CASCADE,
    external_key VARCHAR(120) NOT NULL,
    doc_type VARCHAR(30) NOT NULL,
    title VARCHAR(300),
    source_url VARCHAR(500),
    latest_modify_time BIGINT,
    content_hash VARCHAR(64),
    document_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_external_document_source_key UNIQUE (source_id, external_key),
    CONSTRAINT ck_external_document_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'REMOVED'))
);

CREATE TABLE external_document_revision (
    id UUID PRIMARY KEY,
    external_document_id UUID NOT NULL REFERENCES external_document(id) ON DELETE CASCADE,
    revision_no INT NOT NULL,
    latest_modify_time BIGINT,
    content_hash VARCHAR(64),
    document_id UUID,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_external_document_revision_status CHECK (
        status IN ('ACTIVE', 'SUPERSEDED', 'FAILED')
    )
);

-- At most one RUNNING sync job per source; claim/steal logic lives in the mapper.
CREATE TABLE connector_sync_job (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES connector_source(id) ON DELETE CASCADE,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    lease_expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '15 minutes',
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    scanned_count INT NOT NULL DEFAULT 0,
    updated_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    removed_count INT NOT NULL DEFAULT 0,
    error_summary TEXT,
    CONSTRAINT ck_connector_sync_job_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT ck_connector_sync_job_status CHECK (
        status IN ('RUNNING', 'SUCCESS', 'PARTIAL_FAILED', 'FAILED')
    )
);

-- Functional index trick: keeps the "one RUNNING job per source" invariant.
CREATE UNIQUE INDEX uk_connector_sync_job_one_running
    ON connector_sync_job (source_id) WHERE status = 'RUNNING';

CREATE INDEX idx_external_document_source ON external_document (source_id);
CREATE INDEX idx_connector_sync_job_source ON connector_sync_job (source_id, started_at DESC);
