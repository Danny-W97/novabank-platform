DO $$ BEGIN
    IF EXISTS (SELECT order_id FROM shop_refunds WHERE status IN ('PENDING','COMPLETED')
               GROUP BY order_id HAVING COUNT(*) > 1) THEN
        RAISE EXCEPTION 'PAYMENT_MIGRATION_CONFLICT: multiple active historical refunds; no rows were deleted. Operator review required.';
    END IF;
END $$;

ALTER TABLE shop_orders ADD COLUMN merchant_id VARCHAR(64);
ALTER TABLE shop_orders ALTER COLUMN bank_reference TYPE VARCHAR(255);
ALTER TABLE shop_orders DROP CONSTRAINT ck_shop_orders_status;
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'UNKNOWN'));

CREATE TABLE shop_payment_attempts (
    id VARCHAR(36) PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES shop_orders(id),
    merchant_id VARCHAR(64) NOT NULL,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('BANK', 'CARD', 'STRIPE')),
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    state VARCHAR(24) NOT NULL CHECK (state IN
      ('CREATED', 'PROCESSING', 'AWAITING_AUTHORIZATION', 'UNKNOWN', 'SUCCEEDED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    correlation_state VARCHAR(128) NOT NULL,
    charged_amount NUMERIC(19,2) NOT NULL CHECK (charged_amount > 0),
    charged_currency VARCHAR(3) NOT NULL,
    bank_authorization_id VARCHAR(64) UNIQUE,
    authorization_url TEXT,
    external_payment_id VARCHAR(255) UNIQUE,
    checkout_url TEXT,
    financial_reference VARCHAR(255),
    customer_account_number VARCHAR(26),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_at TIMESTAMPTZ,
    reconciliation_failures INTEGER NOT NULL DEFAULT 0,
    reconciliation_suspended_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX shop_one_active_payment ON shop_payment_attempts(order_id)
    WHERE state IN ('CREATED', 'PROCESSING', 'AWAITING_AUTHORIZATION', 'UNKNOWN', 'SUCCEEDED');
CREATE INDEX shop_payment_reconcile ON shop_payment_attempts(checked_at, id)
    WHERE state IN ('PROCESSING', 'AWAITING_AUTHORIZATION', 'UNKNOWN') AND reconciliation_suspended_at IS NULL;
-- Existing rows deliberately remain unassigned. No FAILED row is presumed unpaid.
-- New attempts on legacy orders are blocked until explicit operator migration.
