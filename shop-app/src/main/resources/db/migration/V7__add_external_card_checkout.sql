ALTER TABLE shop_orders
    ADD COLUMN IF NOT EXISTS payment_provider VARCHAR(32),
    ADD COLUMN IF NOT EXISTS external_payment_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS external_checkout_url TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_orders_external_payment_id
    ON shop_orders (external_payment_id)
    WHERE external_payment_id IS NOT NULL;
