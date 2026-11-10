-- Existing V1 payment rows have no authenticated merchant ownership.
-- Keep them unassigned and inaccessible to merchant-scoped application queries.
-- Backfill only after a separately audited ownership reconciliation.
ALTER TABLE payments ADD COLUMN merchant_id VARCHAR(128);
ALTER TABLE payments DROP CONSTRAINT payments_idempotency_key_key;
CREATE UNIQUE INDEX uq_payments_merchant_idempotency_key
    ON payments (merchant_id, idempotency_key);
CREATE INDEX idx_payments_merchant_payment_id
    ON payments (merchant_id, id);
