ALTER TABLE scheduled_transfers
    ADD COLUMN scheduled_for_at TIMESTAMPTZ;

UPDATE scheduled_transfers
SET scheduled_for_at = next_execution_at;

ALTER TABLE scheduled_transfers
    ALTER COLUMN scheduled_for_at SET NOT NULL;
