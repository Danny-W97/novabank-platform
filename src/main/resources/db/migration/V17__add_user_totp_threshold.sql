ALTER TABLE users
    ADD COLUMN totp_required_above NUMERIC(19, 2);

ALTER TABLE users
    ADD CONSTRAINT ck_users_totp_required_above
        CHECK (totp_required_above IS NULL OR totp_required_above > 0);
