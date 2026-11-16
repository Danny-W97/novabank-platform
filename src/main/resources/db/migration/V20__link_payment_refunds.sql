ALTER TABLE bank_transactions
    ADD COLUMN refund_original_id BIGINT REFERENCES bank_transactions(id);

-- Existing refunds are linked only when their original payment can be identified
-- by order, accounts, amount and currency. Keep one link if legacy data has duplicates.
WITH matching_refunds AS (
    SELECT refund.id AS refund_id,
           original.id AS original_id,
           ROW_NUMBER() OVER (PARTITION BY original.id ORDER BY refund.created_at, refund.id) AS position
    FROM bank_transactions refund
    JOIN bank_transactions original
      ON refund.payment_order_id = 'refund:' || original.payment_order_id
     AND refund.source_account_id = original.destination_account_id
     AND refund.destination_account_id = original.source_account_id
     AND refund.amount = original.amount
     AND refund.currency = original.currency
    WHERE refund.type = 'PAYMENT'
      AND original.type = 'PAYMENT'
      AND original.payment_order_id IS NOT NULL
      AND refund.payment_order_id LIKE 'refund:%'
)
UPDATE bank_transactions transaction
SET refund_original_id = matching.original_id
FROM matching_refunds matching
WHERE transaction.id = matching.refund_id AND matching.position = 1;

CREATE UNIQUE INDEX uk_bank_transactions_refund_original
    ON bank_transactions (refund_original_id)
    WHERE refund_original_id IS NOT NULL;

ALTER TABLE scheduled_transfers
    ADD COLUMN execution_day_of_month INTEGER;

UPDATE scheduled_transfers
SET execution_day_of_month = EXTRACT(DAY FROM next_execution_at AT TIME ZONE 'Europe/Warsaw')
WHERE frequency = 'MONTHLY';

ALTER TABLE scheduled_transfers
    ADD CONSTRAINT ck_scheduled_execution_day
    CHECK (execution_day_of_month IS NULL OR execution_day_of_month BETWEEN 1 AND 31);
