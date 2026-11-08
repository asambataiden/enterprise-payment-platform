package dev.asambataiden.payment.payments.outbox;

import java.util.Objects;
import java.util.UUID;

/** Versioned, tenant-scoped simulation event; no PAN/PII or secrets. */
public record PaymentOutboxEvent(
        int schemaVersion, UUID eventId, String merchantId, UUID paymentId, String targetState) {
    public PaymentOutboxEvent {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported event version");
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(paymentId);
        if (merchantId == null || merchantId.isBlank()) throw new IllegalArgumentException("Missing merchant");
        if (targetState == null || targetState.isBlank()) throw new IllegalArgumentException("Missing state");
    }
}
