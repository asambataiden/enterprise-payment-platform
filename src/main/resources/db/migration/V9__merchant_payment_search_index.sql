-- Milestone 12: match the merchant-owned keyset pagination query.
-- A single ordered index supports both first page and (created_at,id)
-- continuation without indexing low-cardinality mutable status.
-- Assess additional status/reference indexes only with measured workload.
CREATE INDEX idx_payments_merchant_created_id
    ON payments (merchant_id, created_at DESC, id DESC);

-- History queries use merchant + payment ownership and newest-first outbox events.
-- Existing outbox dispatch indexes optimize different predicates.
CREATE INDEX idx_outbox_merchant_payment_created_id
    ON payment_outbox (merchant_id, payment_id, created_at DESC, id DESC);
