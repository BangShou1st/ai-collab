-- P7.1 账号生命周期：邮箱验证状态 + 一次性账号操作令牌（密码重置 / 邮箱验证）
-- 令牌只存 SHA-256 哈希；消费、过期由 UPDATE 原子判定，防止并发重放。
ALTER TABLE app_user ADD COLUMN email_verified boolean NOT NULL DEFAULT false;

CREATE TABLE account_action_token (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_type varchar(30) NOT NULL CHECK (token_type IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    token_hash varchar(64) NOT NULL UNIQUE,
    purpose_email varchar(120),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_account_action_token_user_type ON account_action_token(user_id, token_type);
CREATE INDEX idx_account_action_token_expires ON account_action_token(expires_at);
