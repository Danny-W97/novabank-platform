ALTER TABLE bank_transactions
    ADD COLUMN payment_order_id VARCHAR(100);

CREATE INDEX ix_transactions_payment_order
    ON bank_transactions (payment_order_id)
    WHERE payment_order_id IS NOT NULL;
