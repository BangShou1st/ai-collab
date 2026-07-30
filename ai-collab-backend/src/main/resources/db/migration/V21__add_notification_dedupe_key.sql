ALTER TABLE notification
    ADD COLUMN dedupe_key varchar(160);

CREATE UNIQUE INDEX uq_notification_dedupe_key
    ON notification(dedupe_key)
    WHERE dedupe_key IS NOT NULL;
