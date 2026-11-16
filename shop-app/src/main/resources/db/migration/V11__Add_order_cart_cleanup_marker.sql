ALTER TABLE shop_orders
    ADD COLUMN IF NOT EXISTS cart_cleanup_completed_at TIMESTAMPTZ;

-- Older paid orders used the legacy full-cart cleanup. Mark them as completed so
-- reconciliation cannot apply the new quantity-based cleanup retroactively.
UPDATE shop_orders
SET cart_cleanup_completed_at = COALESCE(updated_at, CURRENT_TIMESTAMP)
WHERE status = 'PAID'
  AND cart_cleanup_completed_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_shop_orders_cart_cleanup
    ON shop_orders (status, cart_cleanup_completed_at)
    WHERE status = 'PAID' AND cart_cleanup_completed_at IS NULL;
