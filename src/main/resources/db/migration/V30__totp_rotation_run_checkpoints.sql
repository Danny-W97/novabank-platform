CREATE TABLE totp_rotation_runs (
    plan_digest VARCHAR(64) PRIMARY KEY,
    after_id BIGINT NOT NULL CHECK (after_id >= 0),
    batch_size INTEGER NOT NULL CHECK (batch_size BETWEEN 1 AND 100),
    inspected INTEGER NOT NULL,
    migrated INTEGER NOT NULL,
    next_checkpoint BIGINT NOT NULL,
    active_key_id VARCHAR(32) NOT NULL,
    configuration_digest VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
