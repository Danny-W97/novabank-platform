ALTER TABLE scheduled_transfers
    ADD COLUMN idempotency_key VARCHAR(64),
    ADD COLUMN first_execution_at TIMESTAMPTZ;

-- Older records have no creation token or immutable original date. Preserve them without deduplicating
-- legitimate schedules; their generated legacy tokens cannot be replayed by an old browser form.
UPDATE scheduled_transfers
SET idempotency_key = 'legacy-scheduled:' || id::text,
    first_execution_at = scheduled_for_at;

ALTER TABLE scheduled_transfers
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN first_execution_at SET NOT NULL,
    ADD CONSTRAINT uk_scheduled_transfers_idempotency UNIQUE (idempotency_key);
