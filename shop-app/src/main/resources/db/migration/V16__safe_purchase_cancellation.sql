-- Additive: never infer a financial refusal from old order status or age.
ALTER TABLE shop_orders DROP CONSTRAINT ck_shop_orders_status;
ALTER TABLE shop_orders ADD CONSTRAINT ck_shop_orders_status CHECK(status IN ('PENDING','PAID','FAILED','UNKNOWN','CANCELLED'));
ALTER TABLE shop_orders ADD COLUMN cancel_requested_at TIMESTAMPTZ;
ALTER TABLE shop_orders ADD COLUMN cancelled_at TIMESTAMPTZ;
CREATE TABLE shop_purchase_cancellation_requests (
    request_id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES shop_orders(id),
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK(state IN ('REQUESTED','REVIEW','CANCELLED','PAID')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE shop_purchase_cancellation_events (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES shop_purchase_cancellation_requests(request_id),
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX shop_cancellation_order ON shop_purchase_cancellation_requests(order_id,created_at DESC);
CREATE INDEX shop_cancellation_due ON shop_orders(created_at,id) WHERE cancelled_at IS NULL AND status <> 'PAID' AND purchase_id IS NOT NULL;
