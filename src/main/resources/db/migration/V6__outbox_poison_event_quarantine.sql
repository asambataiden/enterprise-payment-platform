-- Events that repeatedly fail delivery are quarantined, not silently lost.
-- Operators must investigate and explicitly reset them for retry.
ALTER TABLE payment_outbox
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD COLUMN last_failure_category VARCHAR(32),
    ADD CONSTRAINT chk_outbox_delivery_state CHECK (
        dead_lettered_at IS NULL OR dispatched_at IS NULL
    );

CREATE INDEX idx_outbox_dead_letters
    ON payment_outbox (dead_lettered_at, merchant_id, id)
    WHERE dead_lettered_at IS NOT NULL;
