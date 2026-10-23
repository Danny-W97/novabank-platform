-- Test-environment operator corrections, NOT customer payments/transfers.
-- Only the local DEV Control Center can invoke the allowlisted correction SQL.
CREATE TABLE dev_cash_adjustments (
    request_id UUID PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency CHAR(3) NOT NULL CHECK (currency = 'PLN'),
    balance_before NUMERIC(19, 2) NOT NULL CHECK (balance_before >= 0),
    balance_after NUMERIC(19, 2) NOT NULL CHECK (balance_after >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (balance_after = balance_before + CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)
);
