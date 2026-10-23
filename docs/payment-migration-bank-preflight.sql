-- Read-only inventory AFTER V26/V28. Execute only against the explicitly selected database.
-- No rows are assigned, deleted or financially modified. Counts are not proof of no in-flight payment.
BEGIN TRANSACTION READ ONLY;
SELECT 'unassigned_authorizations' AS issue, COUNT(*) AS affected
FROM payment_authorizations WHERE merchant_id IS NULL;

SELECT 'unmapped_original_payments' AS issue, COUNT(*) AS affected
FROM bank_transactions t WHERE t.type='PAYMENT' AND t.refund_original_id IS NULL
AND NOT EXISTS (SELECT 1 FROM payment_purchase_attempts a WHERE a.bank_reference=t.reference_number);

SELECT 'multiple_original_payments_for_order_and_destination' AS issue, COUNT(*) AS affected
FROM (SELECT payment_order_id,destination_account_id FROM bank_transactions
      WHERE type='PAYMENT' AND refund_original_id IS NULL AND payment_order_id IS NOT NULL
      GROUP BY payment_order_id,destination_account_id HAVING COUNT(*)>1) conflicts;

SELECT 'authorization_financial_state_conflicts' AS issue, COUNT(*) AS affected
FROM payment_authorizations a LEFT JOIN bank_transactions t ON t.idempotency_key=a.payment_idempotency_key
WHERE (a.status IN ('COMPLETED','REDEEMED') AND (t.id IS NULL OR a.bank_reference IS DISTINCT FROM t.reference_number))
OR (a.status IN ('FAILED','PENDING') AND (a.bank_reference IS NOT NULL OR t.id IS NOT NULL));
COMMIT;
