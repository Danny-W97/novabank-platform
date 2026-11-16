CREATE TABLE exchange_quotes (
    idempotency_key VARCHAR(64) PRIMARY KEY,
    owner_email VARCHAR(254) NOT NULL,
    source_currency VARCHAR(3) NOT NULL,
    target_currency VARCHAR(3) NOT NULL,
    source_amount NUMERIC(19,2) NOT NULL,
    target_amount NUMERIC(19,2) NOT NULL,
    applied_rate NUMERIC(19,8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_exchange_quotes_currencies CHECK (source_currency <> target_currency),
    CONSTRAINT ck_exchange_quotes_source_amount CHECK (source_amount > 0),
    CONSTRAINT ck_exchange_quotes_target_amount CHECK (target_amount > 0),
    CONSTRAINT ck_exchange_quotes_rate CHECK (applied_rate > 0)
);
CREATE INDEX ix_exchange_quotes_created ON exchange_quotes (created_at);
