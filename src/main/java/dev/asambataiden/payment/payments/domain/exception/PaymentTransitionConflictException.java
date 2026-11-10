package dev.asambataiden.payment.payments.domain.exception;

/**
 * A committed simulated payment operation cannot apply because its
 * state or idempotency event identity conflicts with the request.
 * No internal payment state or persistence details leave this boundary.
 */
public final class PaymentTransitionConflictException extends RuntimeException {
    public PaymentTransitionConflictException(String diagnostic) {
        super(diagnostic);
    }
}
