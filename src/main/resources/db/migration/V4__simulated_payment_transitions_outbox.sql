-- Milestone 06.3: simulated lifecycle and transactional event outbox.
-- This table is an internal durable message queue; NO publisher or financial processor.
ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT chk_payments_simulated_status
    CHECK (status IN ('RECEIVED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED'));

CREATE TABLE payment_outbox (
    id UUID PRIMARY KEY,
    merchant_id VARCHAR(128) NOT NULL,
    payment_id UUID NOT NULL,
    target_state VARCHAR(20) NOT NULL
        CHECK (target_state IN ('AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'REFUNDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dispatched_at TIMESTAMPTZ,
    CONSTRAINT uq_payment_outbox_merchant_event UNIQUE (merchant_id, id),
    CONSTRAINT fk_payment_outbox_payment_owner FOREIGN KEY (merchant_id, payment_id)
        REFERENCES payments (merchant_id, id)
);

CREATE INDEX idx_payment_outbox_pending
    ON payment_outbox (created_at, id)
    WHERE dispatched_at IS NULL;

-- No publisher/dispatcher is exposed in this increment. Outbox records are
-- created atomically with simulated state changes and optional ledger journals.
