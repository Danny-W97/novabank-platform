ALTER TABLE demo_cards
    ADD COLUMN IF NOT EXISTS daily_limit NUMERIC(19, 2) NOT NULL DEFAULT 5000.00,
    ADD COLUMN IF NOT EXISTS blocked_until TIMESTAMPTZ;

ALTER TABLE demo_cards
    DROP CONSTRAINT IF EXISTS ck_demo_cards_status;

ALTER TABLE demo_cards
    ADD CONSTRAINT ck_demo_cards_status
        CHECK (status IN ('ACTIVE', 'BLOCKED', 'TEMPORARILY_BLOCKED'));

ALTER TABLE demo_cards
    ADD CONSTRAINT ck_demo_cards_daily_limit CHECK (daily_limit > 0);

ALTER TABLE card_transactions
    ADD COLUMN IF NOT EXISTS amount_pln NUMERIC(19, 2),
    ADD COLUMN IF NOT EXISTS channel VARCHAR(20) NOT NULL DEFAULT 'IN_STORE';

UPDATE card_transactions
SET amount_pln = amount
WHERE amount_pln IS NULL;

ALTER TABLE card_transactions
    ALTER COLUMN amount_pln SET NOT NULL;

ALTER TABLE card_transactions
    ADD CONSTRAINT ck_card_transactions_channel
        CHECK (channel IN ('IN_STORE', 'ONLINE'));

ALTER TABLE card_transactions
    ADD CONSTRAINT ck_card_transactions_amount_pln CHECK (amount_pln > 0);
