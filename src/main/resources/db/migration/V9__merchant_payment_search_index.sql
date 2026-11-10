-- Milestone 12: match the merchant-owned keyset pagination query.
-- A single ordered index supports both first page and (created_at,id)
-- continuation without indexing low-cardinality mutable status.
-- Assess additional status/reference indexes only with measured workload.
CREATE INDEX idx_payments_merchant_created_id
    ON payments (merchant_id, created_at DESC, id DESC);
