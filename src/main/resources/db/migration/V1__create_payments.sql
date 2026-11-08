CREATE TABLE payments (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    merchant_reference VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED')),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payments_merchant_reference ON payments (merchant_reference);
