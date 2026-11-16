ALTER TABLE shop_orders
    ADD COLUMN reconciliation_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN reconciliation_suspended_at TIMESTAMPTZ,
    ADD COLUMN reconciliation_last_error VARCHAR(500),
    ADD CONSTRAINT ck_shop_orders_reconciliation_failures
        CHECK (reconciliation_failures BETWEEN 0 AND 3);

CREATE INDEX ix_shop_orders_active_reconciliation
    ON shop_orders (payment_reconciled_at, updated_at, id)
    WHERE reconciliation_suspended_at IS NULL
      AND (bank_authorization_id IS NOT NULL OR payment_idempotency_key LIKE 'card-%');
