CREATE TABLE payment_authorizations (
    id                       VARCHAR(64) PRIMARY KEY,
    shop_order_id            VARCHAR(100) NOT NULL,
    shop_customer_id         VARCHAR(100) NOT NULL,
    merchant_account_number  VARCHAR(26) NOT NULL,
    amount                   NUMERIC(19, 2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    title                    VARCHAR(140) NOT NULL,
    payment_idempotency_key  VARCHAR(64) NOT NULL UNIQUE,
    state                    VARCHAR(128) NOT NULL,
    return_url               VARCHAR(500) NOT NULL,
    status                   VARCHAR(16) NOT NULL,
    source_account_number    VARCHAR(26),
    authorization_code_hash  VARCHAR(64),
    bank_reference           VARCHAR(32),
    failure_reason           VARCHAR(500),
    created_at               TIMESTAMPTZ NOT NULL,
    expires_at               TIMESTAMPTZ NOT NULL,
    completed_at             TIMESTAMPTZ,
    redeemed_at              TIMESTAMPTZ,

    CONSTRAINT ck_payment_auth_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_auth_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REDEEMED')),
    CONSTRAINT ck_payment_auth_merchant CHECK (merchant_account_number ~ '^[0-9]{26}$'),
    CONSTRAINT ck_payment_auth_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_payment_auth_expiry
    ON payment_authorizations (status, expires_at);
