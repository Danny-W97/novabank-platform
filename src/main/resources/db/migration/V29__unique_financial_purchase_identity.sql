ALTER TABLE bank_transactions ADD COLUMN merchant_id VARCHAR(64);
-- Backfill only identities already explicitly recorded in the trusted purchase registry.
UPDATE bank_transactions t SET merchant_id=a.merchant_id
FROM payment_purchase_attempts a WHERE a.bank_reference=t.reference_number;
CREATE UNIQUE INDEX bank_one_successful_purchase
ON bank_transactions(merchant_id,payment_order_id)
WHERE merchant_id IS NOT NULL AND payment_order_id IS NOT NULL AND type='PAYMENT' AND refund_original_id IS NULL;
