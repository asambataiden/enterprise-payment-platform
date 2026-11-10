-- Educational same-database Kafka consumer projection, not a remote payment processor.
CREATE TABLE payment_event_projection (
    merchant_id VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    target_state VARCHAR(20) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (merchant_id, event_id),
    CONSTRAINT fk_projection_committed_event
        FOREIGN KEY (merchant_id, event_id)
        REFERENCES payment_outbox (merchant_id, id),
    CONSTRAINT fk_projection_owned_payment
        FOREIGN KEY (merchant_id, payment_id)
        REFERENCES payments (merchant_id, id)
);
CREATE INDEX idx_projection_payment ON payment_event_projection (merchant_id, payment_id);
