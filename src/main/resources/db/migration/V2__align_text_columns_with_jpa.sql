ALTER TABLE accounts
    ALTER COLUMN account_number TYPE VARCHAR(26),
    ALTER COLUMN currency TYPE VARCHAR(3);

ALTER TABLE bank_transactions
    ALTER COLUMN currency TYPE VARCHAR(3);
