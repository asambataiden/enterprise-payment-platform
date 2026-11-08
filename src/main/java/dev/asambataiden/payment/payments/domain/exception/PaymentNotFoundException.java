package dev.asambataiden.payment.payments.domain.exception;

import java.util.UUID;

/** Requested payment does not exist. Do not expose persistence details. */
public final class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found");
    }
}
