CREATE TABLE totp_consumption (
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_fingerprint CHAR(64) NOT NULL,
    last_step BIGINT NOT NULL,
    used_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, token_fingerprint)
);
