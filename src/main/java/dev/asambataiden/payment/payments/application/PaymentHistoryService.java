package dev.asambataiden.payment.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only, merchant-scoped view of committed simulated lifecycle events.
 * Outbox rows represent business transitions, not proof of broker delivery
 * or externally settled funds.
 */
@Service
public class PaymentHistoryService {
    private static final int PAGE_SIZE = 100;

    private final PaymentService payments;
    private final JdbcTemplate jdbc;

    public PaymentHistoryService(PaymentService payments, JdbcTemplate jdbc) {
        this.payments = Objects.requireNonNull(payments);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly = true)
    public PaymentHistory history(String merchantId, UUID paymentId) {
        // The same ownership rule as GET /payments/{id}: never enumerate
        // another merchant's event IDs, even when the payment ID is known.
        PaymentResult payment = payments.get(merchantId, paymentId);
        List<HistoryEvent> found = jdbc.query("""
                SELECT o.id, o.target_state, o.created_at, o.dispatched_at,
                       l.id AS ledger_entry_id
                FROM payment_outbox o
                LEFT JOIN ledger_entries l
                  ON l.merchant_id = o.merchant_id AND l.event_id = o.id
                 AND l.payment_id = o.payment_id
                WHERE o.merchant_id = ? AND o.payment_id = ?
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT ?
                """, (rs, index) -> new HistoryEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("target_state"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("dispatched_at") == null ? null
                                : rs.getTimestamp("dispatched_at").toInstant(),
                        rs.getObject("ledger_entry_id", UUID.class)),
                merchantId, paymentId, PAGE_SIZE + 1);

        boolean hasMore = found.size() > PAGE_SIZE;
        List<HistoryEvent> events = List.copyOf(found.subList(0, Math.min(found.size(), PAGE_SIZE)));
        return new PaymentHistory(payment, events, hasMore);
    }

    public record HistoryEvent(UUID eventId, String targetState, Instant recordedAt,
                               Instant dispatchedAt, UUID ledgerEntryId) {
    }

    public record PaymentHistory(PaymentResult payment, List<HistoryEvent> events, boolean hasMore) {
        public PaymentHistory {
            events = List.copyOf(events);
        }
    }
}
