ALTER TABLE app_user
    ADD COLUMN system_admin boolean NOT NULL DEFAULT false;

CREATE INDEX idx_app_user_system_admin
    ON app_user(system_admin)
    WHERE system_admin = true;
