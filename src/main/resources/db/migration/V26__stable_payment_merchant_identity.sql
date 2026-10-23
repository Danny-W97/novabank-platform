-- Additive only: no historical row is assigned to an invented merchant.
ALTER TABLE payment_authorizations ADD COLUMN merchant_id VARCHAR(64);

CREATE TABLE payment_purchases (
    merchant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    active_key VARCHAR(64),
    bank_reference VARCHAR(32) UNIQUE REFERENCES bank_transactions(reference_number),
    PRIMARY KEY (merchant_id, order_id)
);

CREATE TABLE payment_purchase_attempts (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    merchant_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('BANK', 'CARD')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'DECLINED', 'SUCCEEDED')),
    merchant_account_number VARCHAR(26) NOT NULL,
    bank_reference VARCHAR(32) UNIQUE REFERENCES bank_transactions(reference_number),
    FOREIGN KEY (merchant_id, order_id) REFERENCES payment_purchases(merchant_id, order_id)
);
CREATE UNIQUE INDEX payment_one_unresolved_purchase
    ON payment_purchase_attempts(merchant_id, order_id) WHERE state IN ('PENDING', 'SUCCEEDED');
CREATE INDEX payment_authorizations_merchant_order ON payment_authorizations(merchant_id, shop_order_id);
