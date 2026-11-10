package dev.asambataiden.payment.payments.outbox;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional local-effect adapter for events delivered by a TRUSTED transport.
 * The adapter validates the event against the committed outbox before processing.
 * Transport-level authentication/authorization must be enforced by the caller.
 */
public final class TrustedPaymentEventConsumer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PaymentInbox inbox;

    public TrustedPaymentEventConsumer(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.inbox = new PaymentInbox(jdbc);
    }

    /**
     * @return true if the local effect was first applied; false on replay.
     *         The effect must only use the same transactional database and
     *         must not invoke external services.
     */
    public boolean consume(String consumerName, PaymentOutboxEvent event, Runnable localDatabaseEffect) {
        Objects.requireNonNull(event);
        Objects.requireNonNull(localDatabaseEffect);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int known = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_outbox
                    WHERE merchant_id = ? AND id = ? AND payment_id = ?
                      AND target_state = ?
                    """, Integer.class, event.merchantId(), event.eventId(),
                    event.paymentId(), event.targetState());
            if (known != 1) {
                throw new SecurityException("Untrusted or mismatched simulated event");
            }
            if (!inbox.register(consumerName, event)) {
                return false;
            }
            localDatabaseEffect.run();
            return true;
        }));
    }
}
