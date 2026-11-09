package dev.asambataiden.payment.payments.messaging.kafka.consumer;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Typed local database effect. Must be invoked inside the inbox transaction.
 * This projection is a learning/read-model example, not money movement.
 */
public final class LocalPaymentEventProjection {
    private final JdbcTemplate jdbc;

    public LocalPaymentEventProjection(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public void record(PaymentOutboxEvent event) {
        Objects.requireNonNull(event);
        jdbc.update("""
            INSERT INTO payment_event_projection (merchant_id,event_id,payment_id,target_state)
            VALUES (?,?,?,?)
            ON CONFLICT (merchant_id,event_id) DO NOTHING
            """, event.merchantId(), event.eventId(), event.paymentId(), event.targetState());
    }
}
