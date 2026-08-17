ALTER TABLE shop_users
    ALTER COLUMN bank_account_number DROP NOT NULL;

ALTER TABLE shop_orders
    ALTER COLUMN customer_account_number DROP NOT NULL;

ALTER TABLE shop_orders
    ADD COLUMN IF NOT EXISTS bank_authorization_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS payment_state VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uk_shop_orders_bank_authorization
    ON shop_orders (bank_authorization_id)
    WHERE bank_authorization_id IS NOT NULL;
