package dev.asambataiden.payment.payments.outbox;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent local inbox receipt. Consumer effects MUST happen in the caller's
 * same transaction; remote side effects cannot be made atomic by this table.
 */
public final class PaymentInbox {
    private final JdbcTemplate jdbc;

    public PaymentInbox(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** Call inside an existing DB transaction, before applying local effects. */
    public boolean register(String consumer, PaymentOutboxEvent event) {
        if (consumer == null || consumer.isBlank() || consumer.length() > 128) {
            throw new IllegalArgumentException("Consumer name required");
        }
        Objects.requireNonNull(event);
        return jdbc.update("""
            INSERT INTO payment_inbox (consumer_name, merchant_id, event_id)
            VALUES (?, ?, ?)
            ON CONFLICT (consumer_name, merchant_id, event_id) DO NOTHING
            """, consumer, event.merchantId(), event.eventId()) == 1;
    }
}
