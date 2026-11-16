ALTER TABLE payment_purchase_attempts ADD COLUMN charged_amount NUMERIC(19,2);
ALTER TABLE payment_purchase_attempts ADD COLUMN charged_currency VARCHAR(3);
ALTER TABLE payment_purchase_attempts ADD COLUMN payment_title VARCHAR(140);
-- No charge details are invented for historical attempts. Newly created attempts fill all snapshots.
