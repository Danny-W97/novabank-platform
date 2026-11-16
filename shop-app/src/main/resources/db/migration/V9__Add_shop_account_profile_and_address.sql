ALTER TABLE shop_users
    ADD COLUMN IF NOT EXISTS first_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS phone VARCHAR(32);

CREATE TABLE IF NOT EXISTS shop_user_addresses (
    user_id          BIGINT PRIMARY KEY REFERENCES shop_users(id) ON DELETE CASCADE,
    recipient_name   VARCHAR(150) NOT NULL,
    street           VARCHAR(150) NOT NULL,
    house_number     VARCHAR(20) NOT NULL,
    apartment_number VARCHAR(20),
    postal_code      VARCHAR(16) NOT NULL,
    city             VARCHAR(100) NOT NULL,
    country          VARCHAR(100) NOT NULL DEFAULT 'Polska',
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
