ALTER TABLE shop_orders
    ADD COLUMN IF NOT EXISTS payment_reconciled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_shop_orders_payment_reconciliation
    ON shop_orders (status, payment_reconciled_at, updated_at)
    WHERE bank_authorization_id IS NOT NULL;
