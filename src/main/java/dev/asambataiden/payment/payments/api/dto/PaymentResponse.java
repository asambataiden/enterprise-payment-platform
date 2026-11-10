package dev.asambataiden.payment.payments.api.dto;

import dev.asambataiden.payment.payments.application.PaymentResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, BigDecimal amount, String currency, String merchantReference,
                              String status, Instant createdAt) {
    public static PaymentResponse from(PaymentResult payment) {
        return new PaymentResponse(payment.id(), payment.amount(), payment.currency(),
                payment.merchantReference(), payment.status(), payment.createdAt());
    }
}
