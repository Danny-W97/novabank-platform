-- Read-only inventory AFTER V13. Do not convert historical FAILED into a proven financial refusal.
BEGIN TRANSACTION READ ONLY;
SELECT 'unassigned_orders' AS issue, COUNT(*) AS affected FROM shop_orders WHERE merchant_id IS NULL;
SELECT 'legacy_orders_with_payment_attachment' AS issue, COUNT(*) AS affected FROM shop_orders
WHERE merchant_id IS NULL AND (payment_idempotency_key IS NOT NULL OR bank_authorization_id IS NOT NULL
OR external_payment_id IS NOT NULL OR status='PAID');
SELECT 'paid_orders_without_reference' AS issue, COUNT(*) AS affected FROM shop_orders
WHERE status='PAID' AND bank_reference IS NULL;
SELECT 'multiple_active_refunds' AS issue, COUNT(*) AS affected
FROM (SELECT order_id FROM shop_refunds WHERE status IN ('PENDING','COMPLETED')
      GROUP BY order_id HAVING COUNT(*)>1) conflicts;
SELECT provider,state,COUNT(*) AS affected FROM shop_payment_attempts
WHERE state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN') GROUP BY provider,state ORDER BY provider,state;
COMMIT;
