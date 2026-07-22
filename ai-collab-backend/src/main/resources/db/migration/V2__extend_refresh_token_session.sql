ALTER TABLE refresh_token
    ADD COLUMN session_id uuid,
    ADD COLUMN session_expires_at timestamptz,
    ADD COLUMN revoke_reason varchar(32),
    ADD COLUMN replaced_by_token_id uuid;

UPDATE refresh_token
SET session_id = gen_random_uuid(),
    session_expires_at = expires_at;

ALTER TABLE refresh_token
    ALTER COLUMN session_id SET NOT NULL,
    ALTER COLUMN session_expires_at SET NOT NULL,
    ADD CONSTRAINT ck_refresh_token_revoke_reason
        CHECK (revoke_reason IS NULL OR revoke_reason IN (
            'ROTATED',
            'LOGOUT',
            'REUSE_DETECTED',
            'EXPIRED',
            'USER_UNAVAILABLE'
        )),
    ADD CONSTRAINT fk_refresh_token_replaced_by_token
        FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_token(id) ON DELETE SET NULL;

CREATE INDEX idx_refresh_token_session ON refresh_token(session_id);
CREATE INDEX idx_refresh_token_session_revoked ON refresh_token(session_id, revoked_at);
