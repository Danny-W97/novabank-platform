ALTER TABLE shop_users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE shop_users
    DROP CONSTRAINT IF EXISTS ck_shop_users_role;

ALTER TABLE shop_users
    ADD CONSTRAINT ck_shop_users_role
        CHECK (role IN ('CUSTOMER', 'ADMIN'));
