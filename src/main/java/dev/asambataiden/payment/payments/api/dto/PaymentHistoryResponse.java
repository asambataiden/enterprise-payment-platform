package dev.asambataiden.payment.payments.api.dto;

import dev.asambataiden.payment.payments.application.PaymentHistoryService.PaymentHistory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bounded history of committed simulated transitions for one owned payment. */
public record PaymentHistoryResponse(
        PaymentResponse payment, List<Event> events, boolean hasMore) {

    public static PaymentHistoryResponse from(PaymentHistory history) {
        return new PaymentHistoryResponse(
                PaymentResponse.from(history.payment()),
                history.events().stream()
                        .map(e -> new Event(e.eventId(), e.targetState(),
                                e.recordedAt(), e.dispatchedAt(), e.ledgerEntryId()))
                        .toList(),
                history.hasMore());
    }

    public record Event(UUID eventId, String targetState, Instant recordedAt,
                        Instant dispatchedAt, UUID ledgerEntryId) {
    }
}
