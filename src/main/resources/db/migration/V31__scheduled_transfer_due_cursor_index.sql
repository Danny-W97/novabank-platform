-- The cursor is immutable even when execution changes the next due time.
CREATE INDEX idx_scheduled_transfers_due_cursor
    ON scheduled_transfers(id) INCLUDE (next_execution_at) WHERE active = true;
