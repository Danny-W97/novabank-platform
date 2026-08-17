ALTER TABLE users
    ADD COLUMN transfer_single_limit NUMERIC(19, 2),
    ADD COLUMN transfer_daily_limit NUMERIC(19, 2),
    ADD COLUMN payment_single_limit NUMERIC(19, 2),
    ADD COLUMN payment_daily_limit NUMERIC(19, 2);

ALTER TABLE users
    ADD CONSTRAINT ck_users_transfer_single_limit
        CHECK (transfer_single_limit IS NULL OR transfer_single_limit > 0),
    ADD CONSTRAINT ck_users_transfer_daily_limit
        CHECK (transfer_daily_limit IS NULL OR transfer_daily_limit > 0),
    ADD CONSTRAINT ck_users_payment_single_limit
        CHECK (payment_single_limit IS NULL OR payment_single_limit > 0),
    ADD CONSTRAINT ck_users_payment_daily_limit
        CHECK (payment_daily_limit IS NULL OR payment_daily_limit > 0);
