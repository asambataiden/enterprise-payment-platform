package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.domain.PaymentState;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class SimulatedPaymentTransitionServiceIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    SimulatedPaymentTransitionService transitions;

    @Autowired
    PaymentService payments;

    @Autowired
    JdbcTemplate jdbc;

    private UUID create(String merchant) {
        return payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("12.50"), "EUR", "transition-test")).id();
    }

    private String status(UUID payment) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment);
    }

    private long count(String sql, UUID payment) {
        return jdbc.queryForObject(sql, Long.class, payment);
    }

    @Test
    void atomicAuthorizationAndCaptureCreateOneJournalAndTwoOutboxEvents() {
        UUID payment = create("merchant-transitions-a");
        assertEquals(PaymentState.AUTHORIZED, transitions.advance(
                "merchant-transitions-a", payment, UUID.randomUUID(), PaymentState.AUTHORIZED));
        assertEquals("AUTHORIZED", status(payment));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));

        UUID captureEvent = UUID.randomUUID();
        assertEquals(PaymentState.CAPTURED, transitions.advance(
                "merchant-transitions-a", payment, captureEvent, PaymentState.CAPTURED));
        assertEquals("CAPTURED", status(payment));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_postings WHERE entry_id IN (SELECT id FROM ledger_entries WHERE payment_id = ?)", payment));
        assertEquals(2, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_entries WHERE event_id = ?", captureEvent));
    }

    @Test
    void fullRefundAppendsCompensatingJournalAndOutboxAtomically() {
        String merchant = "merchant-refund-a";
        UUID payment = create(merchant);
        transitions.advance(merchant, payment, UUID.randomUUID(), PaymentState.AUTHORIZED);
        UUID captureEvent = UUID.randomUUID();
        transitions.advance(merchant, payment, captureEvent, PaymentState.CAPTURED);

        UUID refundEvent = UUID.randomUUID();
        assertEquals(PaymentState.REFUNDED,
                transitions.advance(merchant, payment, refundEvent, PaymentState.REFUNDED));
        assertEquals("REFUNDED", status(payment));
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
        assertEquals(3, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_entries WHERE event_id = ?", refundEvent));

        var postings = jdbc.query("""
                SELECT e.event_id, p.account_id, p.side, p.amount
                FROM ledger_entries e
                JOIN ledger_postings p ON p.entry_id = e.id
                WHERE e.payment_id = ?
                ORDER BY e.created_at, p.line_number
                """, (rs, index) -> rs.getString("account_id") + "|" + rs.getString("side")
                        + "|" + rs.getBigDecimal("amount").toPlainString() + "|"
                        + rs.getObject("event_id", UUID.class), payment);
        assertEquals(4, postings.size());
        assertTrue(postings.contains("simulated-clearing-receivable|DEBIT|12.50|" + captureEvent));
        assertTrue(postings.contains("simulated-merchant-payable|CREDIT|12.50|" + captureEvent));
        assertTrue(postings.contains("simulated-clearing-receivable|CREDIT|12.50|" + refundEvent));
        assertTrue(postings.contains("simulated-merchant-payable|DEBIT|12.50|" + refundEvent));

        // Delivery of the same refund event cannot produce another journal.
        assertEquals(PaymentState.REFUNDED,
                transitions.advance(merchant, payment, refundEvent, PaymentState.REFUNDED));
        assertEquals(2, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
        assertEquals(3, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
        assertThrows(IllegalStateException.class,
                () -> transitions.advance(merchant, payment, UUID.randomUUID(), PaymentState.REFUNDED));
    }

    @Test
    void refundLedgerConflictRollsBackStatusAndOutbox() {
        String merchant = "merchant-refund-rollback";
        UUID payment = create(merchant);
        transitions.advance(merchant, payment, UUID.randomUUID(), PaymentState.AUTHORIZED);
        transitions.advance(merchant, payment, UUID.randomUUID(), PaymentState.CAPTURED);

        // A legacy journal-only event is enough to collide with the ledger's
        // unique event key; the enclosing payment transaction must roll back.
        UUID priorEvent = UUID.randomUUID();
        UUID otherPayment = create(merchant);
        UUID journalId = UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> {
                    jdbc.update("""
                            INSERT INTO ledger_entries (id, merchant_id, payment_id, event_id, currency)
                            VALUES (?, ?, ?, ?, 'EUR')
                            """, journalId, merchant, otherPayment, priorEvent);
                    jdbc.update("""
                            INSERT INTO ledger_postings (entry_id, line_number, account_id, side, amount)
                            VALUES (?, 1, 'a', 'DEBIT', 12.50), (?, 2, 'b', 'CREDIT', 12.50)
                            """, journalId, journalId);
                    jdbc.update("UPDATE ledger_entries SET finalized = TRUE WHERE id = ?", journalId);
                });

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> transitions.advance(merchant, payment, priorEvent, PaymentState.REFUNDED));
        assertEquals("CAPTURED", status(payment));
        assertEquals(1, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
        assertEquals(0, count("""
                SELECT COUNT(*) FROM payment_outbox
                WHERE payment_id = ? AND target_state = 'REFUNDED'
                """, payment));
    }

    @Test
    void replayIsIdempotentButEventReuseWithDifferentStateIsRejected() {
        UUID payment = create("merchant-transitions-a");
        UUID eventId = UUID.randomUUID();
        transitions.advance("merchant-transitions-a", payment, eventId, PaymentState.AUTHORIZED);
        assertEquals(PaymentState.AUTHORIZED,
                transitions.advance("merchant-transitions-a", payment, eventId, PaymentState.AUTHORIZED));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
        assertThrows(IllegalStateException.class, () ->
                transitions.advance("merchant-transitions-a", payment, eventId, PaymentState.CAPTURED));
        assertEquals("AUTHORIZED", status(payment));
    }

    @Test
    void rejectsUnownedPaymentWithoutAnyWrites() {
        UUID payment = create("merchant-transitions-a");
        assertThrows(IllegalArgumentException.class, () ->
                transitions.advance("merchant-transitions-b", payment, UUID.randomUUID(), PaymentState.AUTHORIZED));
        assertEquals("RECEIVED", status(payment));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
    }

    @Test
    void invalidCaptureBeforeAuthorizationHasNoSideEffects() {
        UUID payment = create("merchant-transitions-a");
        assertThrows(IllegalStateException.class, () ->
                transitions.advance("merchant-transitions-a", payment, UUID.randomUUID(), PaymentState.CAPTURED));
        assertEquals("RECEIVED", status(payment));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
    }

    @Test
    void outboxFailureRollsBackPaymentStateAndCaptureJournal() {
        UUID payment = create("merchant-transitions-a");
        UUID event = UUID.randomUUID();
        transitions.advance("merchant-transitions-a", payment, event, PaymentState.AUTHORIZED);
        // Different payment already holds this same merchant/event ID in the outbox.
        UUID otherPayment = create("merchant-transitions-a");
        UUID sharedEvent = UUID.randomUUID();
        transitions.advance("merchant-transitions-a", otherPayment, sharedEvent, PaymentState.AUTHORIZED);

        assertThrows(IllegalStateException.class, () ->
                transitions.advance("merchant-transitions-a", payment, sharedEvent, PaymentState.CAPTURED));
        assertEquals("AUTHORIZED", status(payment));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
    }

    @Test
    void databaseConstraintFailureRollsBackCaptureStateAndOutbox() {
        UUID payment = create("merchant-transitions-a");
        transitions.advance("merchant-transitions-a", payment, UUID.randomUUID(), PaymentState.AUTHORIZED);
        // Collide with the journal event uniqueness constraint, without an
        // existing outbox row (simulates a legacy internal writer).
        UUID journalOnlyEvent = UUID.randomUUID();
        UUID secondPayment = create("merchant-transitions-a");
        UUID journalId = UUID.randomUUID();
        org.springframework.transaction.support.TransactionTemplate transaction =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(state -> {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, merchant_id, payment_id, event_id, currency)
                    VALUES (?, ?, ?, ?, 'EUR')
                    """, journalId, "merchant-transitions-a", secondPayment, journalOnlyEvent);
            jdbc.update("""
                    INSERT INTO ledger_postings (entry_id, line_number, account_id, side, amount)
                    VALUES (?, 1, 'a', 'DEBIT', 12.50), (?, 2, 'b', 'CREDIT', 12.50)
                    """, journalId, journalId);
            jdbc.update("UPDATE ledger_entries SET finalized = TRUE WHERE id = ?", journalId);
        });
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                transitions.advance("merchant-transitions-a", payment, journalOnlyEvent, PaymentState.CAPTURED));
        assertEquals("AUTHORIZED", status(payment));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ? AND target_state = 'CAPTURED'", payment));
        assertEquals(0, count("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", payment));
    }

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void concurrentTransitionsSerializeAndOnlyOneWins() throws Exception {
        UUID payment = create("merchant-transitions-a");
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            var worker = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test synchronization timeout");
                }
                try {
                    transitions.advance("merchant-transitions-a", payment, UUID.randomUUID(),
                            PaymentState.AUTHORIZED);
                    return true;
                } catch (IllegalStateException expected) {
                    return false;
                }
            };
            var first = pool.submit(worker);
            var second = pool.submit(worker);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            int winners = (first.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
        }
        assertEquals("AUTHORIZED", status(payment));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_outbox WHERE payment_id = ?", payment));
    }
}
