ALTER TABLE demo_cards
    ADD COLUMN IF NOT EXISTS online_number VARCHAR(16);

UPDATE demo_cards
SET online_number = LPAD(id::text, 16, '0')
WHERE online_number IS NULL;

ALTER TABLE demo_cards
    ALTER COLUMN online_number SET NOT NULL;

ALTER TABLE demo_cards
    ADD CONSTRAINT uk_demo_cards_online_number UNIQUE (online_number);

ALTER TABLE demo_cards
    ADD CONSTRAINT ck_demo_cards_online_number CHECK (online_number ~ '^[0-9]{16}$');
