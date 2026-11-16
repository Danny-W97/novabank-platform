ALTER TABLE demo_cards
    ADD COLUMN IF NOT EXISTS demo_cvv VARCHAR(3),
    ADD COLUMN IF NOT EXISTS demo_expires_at DATE;

UPDATE demo_cards
SET demo_cvv = LPAD((100 + FLOOR(random() * 900))::int::text, 3, '0')
WHERE demo_cvv IS NULL;

UPDATE demo_cards
SET demo_expires_at = (CURRENT_DATE + INTERVAL '3 years')::date
WHERE demo_expires_at IS NULL;

ALTER TABLE demo_cards
    ALTER COLUMN demo_cvv SET NOT NULL,
    ALTER COLUMN demo_expires_at SET NOT NULL;

ALTER TABLE demo_cards
    ADD CONSTRAINT ck_demo_cards_demo_cvv CHECK (demo_cvv ~ '^[0-9]{3}$');
