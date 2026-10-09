ALTER TABLE agent_run ADD COLUMN active_elapsed_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN claim_started_at TIMESTAMPTZ;
