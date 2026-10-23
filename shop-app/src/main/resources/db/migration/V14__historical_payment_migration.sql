-- Explicit operator import only. Existing financial/customer rows are never deleted.
ALTER TABLE shop_orders ADD COLUMN historical_imported_at TIMESTAMPTZ;
CREATE TABLE historical_payment_migration_runs (
    plan_digest VARCHAR(64) PRIMARY KEY,
    manifest JSONB NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('APPLYING','COMPLETED')),
    completed_orders INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
