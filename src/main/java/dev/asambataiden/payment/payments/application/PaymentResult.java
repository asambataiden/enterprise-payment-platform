package dev.asambataiden.payment.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Application result independent of HTTP and persistence frameworks. */
public record PaymentResult(
        UUID id,
        BigDecimal amount,
        String currency,
        String merchantReference,
        String status,
        Instant createdAt) {
}
