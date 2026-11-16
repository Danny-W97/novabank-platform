ALTER TABLE users
    ADD COLUMN totp_secret_encrypted TEXT,
    ADD COLUMN totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN totp_enabled_at TIMESTAMPTZ;

CREATE TABLE totp_recovery_codes (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   VARCHAR(100) NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_totp_recovery_codes_user_unused
    ON totp_recovery_codes (user_id, used_at);
