-- Durable, broker-agnostic delivery with expiring claims and fencing tokens.
-- Failed publishing cannot roll back a committed payment or journal.
ALTER TABLE payment_outbox
    ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token UUID,
    ADD COLUMN leased_until TIMESTAMPTZ,
    ADD CONSTRAINT chk_outbox_claim_pair CHECK ((lease_token IS NULL) = (leased_until IS NULL));

CREATE INDEX idx_outbox_claimable ON payment_outbox
    (next_attempt_at, created_at, merchant_id, id)
    WHERE dispatched_at IS NULL;

-- One consumer stores a durable idempotency receipt; external effects must
-- participate in the same DB transaction to achieve exactly-once local effects.
CREATE TABLE payment_inbox (
    consumer_name VARCHAR(128) NOT NULL CHECK (length(trim(consumer_name)) > 0),
    merchant_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (consumer_name, merchant_id, event_id),
    CONSTRAINT fk_inbox_outbox_event FOREIGN KEY (merchant_id, event_id)
        REFERENCES payment_outbox (merchant_id, id)
);
