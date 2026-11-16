-- Only the guarded cancellation service may record this financial outcome.
-- Existing UNKNOWN cases are intentionally left unchanged.
ALTER TABLE shop_payment_cases DROP CONSTRAINT shop_payment_cases_financial_outcome_check;
ALTER TABLE shop_payment_cases ADD CONSTRAINT shop_payment_cases_financial_outcome_check
    CHECK (financial_outcome IN ('UNKNOWN','PAID','DECLINED','REFUNDED','CONFLICT','REFUND_REVIEW','CANCELLED'));
