package dev.asambataiden.payment.payments.domain.exception;

/** A retry reused an idempotency key with different payment terms. */
public final class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key already used with different request");
    }
}
