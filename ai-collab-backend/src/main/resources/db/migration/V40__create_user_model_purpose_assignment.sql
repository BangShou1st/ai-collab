-- V40: per-purpose override of the user default AI provider.
-- No row for a purpose means that purpose uses the user's is_default provider.

CREATE TABLE user_model_purpose_assignment (
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL,
    provider_id UUID NOT NULL REFERENCES user_ai_provider(id) ON DELETE CASCADE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_model_purpose_assignment PRIMARY KEY (user_id, purpose),
    CONSTRAINT ck_user_model_purpose CHECK (
        purpose IN ('KNOWLEDGE_CHAT', 'AGENT', 'PLANNING')
    )
);
CREATE INDEX idx_user_model_purpose_provider ON user_model_purpose_assignment (provider_id);
