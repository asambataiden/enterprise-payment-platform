package dev.asambataiden.payment.payments.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly invoked, broker-independent dispatcher. No scheduler or transport
 * is registered: deployment must supply a publisher, rate limit and supervision.
 *
 * Each DB transaction claims/ACKs one row; publishing is OUTSIDE a DB transaction.
 * Lease expiration and fencing prevent a stale publisher from acknowledging an
 * event claimed by a later worker. Duplicates after a crash are expected.
 */
public final class PaymentOutboxDispatcher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PaymentEventPublisher publisher;

    public PaymentOutboxDispatcher(
            JdbcTemplate jdbc, PlatformTransactionManager manager, PaymentEventPublisher publisher) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.publisher = Objects.requireNonNull(publisher);
    }

    /** At most one event per invocation; returns false if there is no eligible work. */
    public boolean dispatchOne() {
        Claim claim = transactions.execute(status -> claimNext());
        if (claim == null) return false;
        try {
            publisher.publish(claim.event());
            transactions.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                    UPDATE payment_outbox
                    SET dispatched_at = CURRENT_TIMESTAMP, lease_token = NULL, leased_until = NULL
                    WHERE merchant_id = ? AND id = ? AND lease_token = ? AND dispatched_at IS NULL
                    """, claim.event().merchantId(), claim.event().eventId(), claim.token());
                // A lease might have expired and been re-claimed: do not overwrite
                // a newer owner's claim or assert that delivery was acknowledged.
                if (updated != 1) throw new StaleLeaseException();
            });
        } catch (StaleLeaseException stale) {
            // Claim ownership is gone. The event remains available for redelivery.
        } catch (Exception publishFailure) {
            transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE payment_outbox
                SET lease_token = NULL, leased_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP
                        + (LEAST(POWER(2.0, LEAST(delivery_attempts, 8)), 300) * INTERVAL '1 second')
                WHERE merchant_id = ? AND id = ? AND lease_token = ? AND dispatched_at IS NULL
                """, claim.event().merchantId(), claim.event().eventId(), claim.token()));
        }
        return true;
    }

    private Claim claimNext() {
        UUID token = UUID.randomUUID();
        List<Claim> claims = jdbc.query("""
            WITH next_event AS (
                SELECT merchant_id, id FROM payment_outbox
                WHERE dispatched_at IS NULL
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND (leased_until IS NULL OR leased_until <= CURRENT_TIMESTAMP)
                ORDER BY created_at, merchant_id, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE payment_outbox e
            SET lease_token = ?,
                leased_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                delivery_attempts = delivery_attempts + 1
            FROM next_event n
            WHERE e.merchant_id = n.merchant_id AND e.id = n.id
            RETURNING e.id, e.merchant_id, e.payment_id, e.target_state
            """, (rs, row) -> new Claim(
                new PaymentOutboxEvent(1,
                    rs.getObject("id", UUID.class), rs.getString("merchant_id"),
                    rs.getObject("payment_id", UUID.class), rs.getString("target_state")),
                token), token);
        return claims.isEmpty() ? null : claims.getFirst();
    }

    private record Claim(PaymentOutboxEvent event, UUID token) {}
    private static final class StaleLeaseException extends RuntimeException {}
}
