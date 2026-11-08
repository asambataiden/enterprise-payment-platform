package dev.asambataiden.payment.payments.outbox;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class PaymentOutboxDispatcherIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private UUID event(String merchant) {
        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("11.00"), "EUR", "outbox-test")).id();
        UUID eventId = UUID.randomUUID();
        transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED);
        return eventId;
    }

    private long count(String sql, UUID event) {
        return jdbc.queryForObject(sql, Long.class, event);
    }

    @Test
    void successfulDeliveryMarksDispatchedAndDoesNotPublishAgain() {
        UUID event = event("outbox-owner-a");
        List<PaymentOutboxEvent> sent = new ArrayList<>();
        var dispatcher = new PaymentOutboxDispatcher(jdbc, manager, sent::add);
        // Tests isolate pending work by forcing just this row eligible; earlier
        // unprocessed tests may also leave rows, so claim the oldest matching row is not assumed.
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE dispatched_at IS NULL AND id <> ?", event);
        assertTrue(dispatcher.dispatchOne());
        assertEquals(event, sent.getFirst().eventId());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_outbox WHERE id = ? AND dispatched_at IS NOT NULL", event));
        assertFalse(dispatcher.dispatchOne());
    }

    @Test
    void failedTransportKeepsEventPendingAndRecordsBackoff() {
        UUID event = event("outbox-owner-a");
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE dispatched_at IS NULL AND id <> ?", event);
        var failing = new PaymentOutboxDispatcher(jdbc, manager, e -> {
            throw new IllegalStateException("simulated broker outage");
        });
        assertTrue(failing.dispatchOne());
        assertEquals(1, count("SELECT delivery_attempts FROM payment_outbox WHERE id = ?", event));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_outbox WHERE id = ? AND dispatched_at IS NULL AND lease_token IS NULL AND next_attempt_at > CURRENT_TIMESTAMP", event));

        // Simulate backoff elapsed, not a real clock sleep.
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE id = ?", event);
        List<PaymentOutboxEvent> delivered = new ArrayList<>();
        assertTrue(new PaymentOutboxDispatcher(jdbc, manager, delivered::add).dispatchOne());
        assertEquals(event, delivered.getFirst().eventId());
        assertEquals(2, count("SELECT delivery_attempts FROM payment_outbox WHERE id = ?", event));
    }

    @Test
    void expiredLeaseCanBeReclaimedAndOldAckCannotOverwriteClaim() {
        UUID event = event("outbox-owner-a");
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE dispatched_at IS NULL AND id <> ?", event);
        UUID staleToken = UUID.randomUUID();
        jdbc.update("""
                UPDATE payment_outbox
                SET lease_token = ?, leased_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, staleToken, event);
        List<PaymentOutboxEvent> delivered = new ArrayList<>();
        assertTrue(new PaymentOutboxDispatcher(jdbc, manager, delivered::add).dispatchOne());
        assertEquals(event, delivered.getFirst().eventId());
        assertEquals(0, jdbc.update("""
                UPDATE payment_outbox SET dispatched_at = CURRENT_TIMESTAMP
                WHERE id = ? AND lease_token = ? AND dispatched_at IS NULL
                """, event, staleToken));
    }

    @Test
    void inboxDeduplicatesPerConsumerAndMerchantAndRollsBackWithLocalEffect() {
        UUID eventId = event("outbox-owner-a");
        var envelope = new PaymentOutboxEvent(1, eventId, "outbox-owner-a",
                jdbc.queryForObject("SELECT payment_id FROM payment_outbox WHERE id = ?", UUID.class, eventId),
                "AUTHORIZED");
        var inbox = new PaymentInbox(jdbc);
        var tx = new TransactionTemplate(manager);
        assertTrue(tx.execute(s -> inbox.register("example-consumer", envelope)));
        assertFalse(tx.execute(s -> inbox.register("example-consumer", envelope)));
        assertTrue(tx.execute(s -> inbox.register("independent-consumer", envelope)));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            assertTrue(inbox.register("rolled-back-consumer", envelope));
            throw new IllegalStateException("local effect failed");
        }));
        assertTrue(tx.execute(s -> inbox.register("rolled-back-consumer", envelope)));
    }

    @Test
    void concurrentDispatchersCannotClaimTheSamePendingEvent() throws Exception {
        UUID event = event("outbox-owner-a");
        jdbc.update("UPDATE payment_outbox SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE dispatched_at IS NULL AND id <> ?", event);
        AtomicInteger published = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> worker = () -> {
                var dispatcher = new PaymentOutboxDispatcher(jdbc, manager, e -> published.incrementAndGet());
                ready.countDown();
                assertTrue(go.await(10, TimeUnit.SECONDS));
                return dispatcher.dispatchOne();
            };
            var first = pool.submit(worker);
            var second = pool.submit(worker);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, published.get());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_outbox WHERE id = ? AND dispatched_at IS NOT NULL", event));
    }
}
