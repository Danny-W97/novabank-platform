ALTER TABLE admin_status_changes
    ALTER COLUMN account_number TYPE VARCHAR(26)
    USING TRIM(account_number);
