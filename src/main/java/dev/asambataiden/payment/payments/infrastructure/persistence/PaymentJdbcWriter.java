package dev.asambataiden.payment.payments.infrastructure.persistence;

import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL provides the atomic uniqueness guarantee for payment idempotency.
 * This insert participates in the caller's Spring-managed transaction.
 */
@Repository
public class PaymentJdbcWriter {
    private static final String INSERT_IF_ABSENT = """
            INSERT INTO payments
                (id, merchant_id, idempotency_key, amount, currency, merchant_reference, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private final JdbcTemplate jdbc;

    public PaymentJdbcWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertIfAbsent(Payment payment) {
        var insertedIds = jdbc.queryForList(
                INSERT_IF_ABSENT,
                UUID.class,
                payment.getId(),
                payment.getMerchantId(),
                payment.getIdempotencyKey(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getMerchantReference(),
                payment.getStatus(),
                Timestamp.from(payment.getCreatedAt()));
        return !insertedIds.isEmpty();
    }
}
