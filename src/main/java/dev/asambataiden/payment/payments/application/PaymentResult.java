package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.infrastructure.persistence.Payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Application result, independent of HTTP and JSON. */
public record PaymentResult(
        UUID id,
        BigDecimal amount,
        String currency,
        String merchantReference,
        String status,
        Instant createdAt) {

    static PaymentResult from(Payment payment) {
        return new PaymentResult(
                payment.getId(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantReference(), payment.getStatus(), payment.getCreatedAt());
    }
}
