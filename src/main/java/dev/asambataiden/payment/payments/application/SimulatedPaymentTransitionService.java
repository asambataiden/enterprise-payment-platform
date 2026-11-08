package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.ledger.application.SimulatedLedgerService;
import dev.asambataiden.payment.ledger.domain.JournalEntry;
import dev.asambataiden.payment.ledger.domain.LedgerPosting;
import dev.asambataiden.payment.ledger.domain.PostingSide;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.payments.domain.PaymentStateMachine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal orchestration only; no external event consumer or HTTP route.
 * Payment, journal (when applicable), and outbox share one JDBC transaction.
 */
@Service
public class SimulatedPaymentTransitionService {
    private final JdbcTemplate jdbc;
    private final SimulatedLedgerService ledger;

    public SimulatedPaymentTransitionService(JdbcTemplate jdbc, SimulatedLedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    @Transactional
    public PaymentState advance(String merchantId, UUID paymentId, UUID eventId, PaymentState target) {
        if (merchantId == null || merchantId.isBlank() || merchantId.length() > 128
                || paymentId == null || eventId == null || target == null) {
            throw new IllegalArgumentException("Merchant, payment, event and target state are required");
        }

        // Serialize concurrent events for the SAME merchant-owned payment.
        var payments = jdbc.query("""
                SELECT status, amount, currency FROM payments
                WHERE id = ? AND merchant_id = ? FOR UPDATE
                """, (rs, row) -> new PaymentSnapshot(
                        PaymentState.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"), rs.getString("currency")),
                paymentId, merchantId);
        if (payments.isEmpty()) {
            throw new IllegalArgumentException("Payment does not exist for merchant");
        }
        PaymentSnapshot current = payments.getFirst();

        // A retry of the SAME event is idempotent; reuse for a DIFFERENT
        // payment or target state must be rejected, not silently acknowledged.
        var previous = jdbc.query("""
                SELECT payment_id, target_state FROM payment_outbox
                WHERE merchant_id = ? AND id = ?
                """, (rs, row) -> new EventSnapshot(
                        rs.getObject("payment_id", UUID.class),
                        PaymentState.valueOf(rs.getString("target_state"))),
                merchantId, eventId);
        if (!previous.isEmpty()) {
            EventSnapshot old = previous.getFirst();
            if (!old.paymentId().equals(paymentId) || old.target() != target) {
                throw new IllegalStateException("Simulated event ID reused for different transition");
            }
            return old.target();
        }

        PaymentStateMachine.transition(current.state(), target);
        if (jdbc.update("""
                UPDATE payments SET status = ?
                WHERE id = ? AND merchant_id = ? AND status = ?
                """, target.name(), paymentId, merchantId, current.state().name()) != 1) {
            throw new IllegalStateException("Concurrent state update rejected");
        }

        // Only a simulated CAPTURE creates the balanced receivable/clearing
        // journal. No arbitrary account or monetary amount comes from caller.
        if (target == PaymentState.CAPTURED) {
            var entry = new JournalEntry(UUID.randomUUID(), merchantId, paymentId,
                    current.currency(), List.of(
                        new LedgerPosting("simulated-clearing-receivable", PostingSide.DEBIT, current.amount()),
                        new LedgerPosting("simulated-merchant-payable", PostingSide.CREDIT, current.amount())));
            ledger.append(eventId, entry);
        }

        jdbc.update("""
                INSERT INTO payment_outbox (id, merchant_id, payment_id, target_state)
                VALUES (?, ?, ?, ?)
                """, eventId, merchantId, paymentId, target.name());
        return target;
    }

    private record PaymentSnapshot(PaymentState state, BigDecimal amount, String currency) {}
    private record EventSnapshot(UUID paymentId, PaymentState target) {}
}
