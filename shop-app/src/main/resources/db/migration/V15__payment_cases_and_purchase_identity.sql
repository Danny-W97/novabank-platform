-- Additive: no historical payment result is inferred and no cart is cleared here.
ALTER TABLE shop_carts ADD COLUMN purchase_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE shop_carts ADD COLUMN independent_purchase_at TIMESTAMPTZ;
ALTER TABLE shop_carts ADD COLUMN requires_independent_ack BOOLEAN NOT NULL DEFAULT FALSE;
-- Existing baskets have no reliable historical purchase identity. Preserve them, but do not silently resubmit them.
UPDATE shop_carts SET requires_independent_ack=TRUE;
ALTER TABLE shop_orders ADD COLUMN purchase_id UUID;
CREATE UNIQUE INDEX shop_order_purchase_identity ON shop_orders(customer_id,purchase_id) WHERE purchase_id IS NOT NULL;

CREATE TABLE shop_purchase_snapshots (
    purchase_id UUID PRIMARY KEY,
    customer_id VARCHAR(128) NOT NULL,
    snapshot JSONB NOT NULL,
    replacement_purchase_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shop_payment_cases (
    order_id BIGINT PRIMARY KEY REFERENCES shop_orders(id),
    workflow_status VARCHAR(32) NOT NULL DEFAULT 'OPEN'
        CHECK(workflow_status IN ('OPEN','ESCALATED','CLOSED_UNRESOLVED','RESOLVED')),
    financial_outcome VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        CHECK(financial_outcome IN ('UNKNOWN','PAID','DECLINED','REFUNDED','CONFLICT','REFUND_REVIEW')),
    allow_new_purchase_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    checked_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shop_payment_case_events (
    request_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES shop_orders(id),
    action VARCHAR(40) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    expected_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    evidence_digest VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shop_payment_case_plans (
    digest VARCHAR(64) PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES shop_orders(id),
    resolution VARCHAR(24) NOT NULL,
    manifest JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','APPLYING','COMPLETED')),
    apply_request_id UUID UNIQUE,
    apply_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP + INTERVAL '1 hour'
);
CREATE INDEX shop_case_events_order ON shop_payment_case_events(order_id,created_at DESC);
CREATE INDEX shop_case_plans_order ON shop_payment_case_plans(order_id,created_at DESC);
