ALTER TABLE card_transactions
    ADD COLUMN merchant_account_number VARCHAR(26),
    ADD COLUMN order_id VARCHAR(100),
    ADD COLUMN payment_title VARCHAR(140),
    ADD COLUMN bank_reference VARCHAR(32);

UPDATE card_transactions card
SET merchant_account_number = destination.account_number,
    order_id = transaction.payment_order_id,
    payment_title = transaction.title,
    bank_reference = transaction.reference_number
FROM bank_transactions transaction
JOIN accounts destination ON destination.id = transaction.destination_account_id
WHERE card.idempotency_key = transaction.idempotency_key
  AND card.channel = 'ONLINE'
  AND transaction.type = 'PAYMENT';
