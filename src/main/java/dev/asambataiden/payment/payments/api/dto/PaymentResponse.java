package dev.asambataiden.payment.payments.api.dto;

import dev.asambataiden.payment.payments.infrastructure.persistence.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID id, BigDecimal amount, String currency, String merchantReference,
                              String status, Instant createdAt) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantReference(), payment.getStatus(), payment.getCreatedAt());
    }
}
