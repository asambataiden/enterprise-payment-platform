package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.payments.messaging.kafka.consumer.LocalPaymentEventProjection;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxDispatcher;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;
import dev.asambataiden.payment.payments.outbox.TrustedPaymentEventConsumer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One business-facing acceptance journey through the real application,
 * ledger, outbox, inbox and PostgreSQL projection. Delivery is simulated
 * in-process: this test deliberately does NOT claim Kafka network coverage
 * or external payment processing.
 */
@Testcontainers
@SpringBootTest
class SimulatedPaymentLifecycleAcceptanceIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void createAuthorizeCaptureRefundAndProjectWithoutDuplicateBusinessEffects() {
        String merchant = "merchant-acceptance-" + UUID.randomUUID();
        String key = "acceptance-" + UUID.randomUUID();
        var command = new CreatePaymentCommand(new BigDecimal("12.50"), "EUR", "order-acceptance");
        UUID payment = payments.create(merchant, key, command).id();
        assertEquals(payment, payments.create(merchant, key, command).id());
        assertEquals("RECEIVED", payments.get(merchant, payment).status());

        UUID authorized = UUID.randomUUID();
        UUID captured = UUID.randomUUID();
        UUID refunded = UUID.randomUUID();
        assertEquals(PaymentState.AUTHORIZED,
                transitions.advance(merchant, payment, authorized, PaymentState.AUTHORIZED));
        assertEquals(PaymentState.CAPTURED,
                transitions.advance(merchant, payment, captured, PaymentState.CAPTURED));
        assertEquals(PaymentState.REFUNDED,
                transitions.advance(merchant, payment, refunded, PaymentState.REFUNDED));
        assertEquals("REFUNDED", payments.get(merchant, payment).status());

        assertEquals(3L, count("""
                SELECT COUNT(*) FROM payment_outbox WHERE merchant_id = ? AND payment_id = ?
                """, merchant, payment));
        assertEquals(2L, count("""
                SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = ? AND payment_id = ?
                """, merchant, payment));

        // Append-only reversal: net account balances are zero after a full
        // refund; neither the capture nor its compensating journal disappears.
        var netBalances = jdbc.query("""
                SELECT p.account_id,
                    SUM(CASE WHEN p.side = 'DEBIT' THEN p.amount ELSE -p.amount END) AS net
                FROM ledger_entries e JOIN ledger_postings p ON p.entry_id = e.id
                WHERE e.merchant_id = ? AND e.payment_id = ?
                GROUP BY p.account_id
                """, (rs, i) -> rs.getBigDecimal("net"), merchant, payment);
        assertEquals(2, netBalances.size());
        netBalances.forEach(net -> assertEquals(0, net.compareTo(BigDecimal.ZERO)));

        // Do not let any unrelated test-owned outbox rows be selected.
        jdbc.update("""
                UPDATE payment_outbox
                SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour'
                WHERE dispatched_at IS NULL AND (merchant_id <> ? OR payment_id <> ?)
                """, merchant, payment);

        var trustedConsumer = new TrustedPaymentEventConsumer(jdbc, transactionManager);
        var projection = new LocalPaymentEventProjection(jdbc);
        List<PaymentOutboxEvent> delivered = new ArrayList<>();
        var dispatcher = new PaymentOutboxDispatcher(jdbc, transactionManager, event -> {
            delivered.add(event);
            assertTrue(trustedConsumer.consume(
                    "acceptance-projection", event, () -> projection.record(event)));
        });
        for (int i = 0; i < 3; i++) {
            assertTrue(dispatcher.dispatchOne(), "An outbox event must be available");
        }
        assertFalse(dispatcher.dispatchOne());
        assertEquals(3, delivered.size());
        assertEquals(java.util.Set.of(authorized, captured, refunded),
                delivered.stream().map(PaymentOutboxEvent::eventId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM payment_event_projection WHERE merchant_id = ? AND payment_id = ?
                """, merchant, payment));
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM payment_outbox
                WHERE merchant_id = ? AND payment_id = ? AND dispatched_at IS NOT NULL
                """, merchant, payment));

        // Simulate an at-least-once duplicate after delivery acknowledgement.
        var replay = delivered.getFirst();
        assertFalse(trustedConsumer.consume(
                "acceptance-projection", replay, () -> projection.record(replay)));
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM payment_event_projection WHERE merchant_id = ? AND payment_id = ?
                """, merchant, payment));
        assertEquals(2L, count("""
                SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = ? AND payment_id = ?
                """, merchant, payment));
        assertEquals("REFUNDED", payments.get(merchant, payment).status());
    }

    private long count(String sql, String merchant, UUID payment) {
        return jdbc.queryForObject(sql, Long.class, merchant, payment);
    }
}
