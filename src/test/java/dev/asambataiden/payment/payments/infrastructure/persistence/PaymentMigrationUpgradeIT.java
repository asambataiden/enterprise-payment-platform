package dev.asambataiden.payment.payments.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrade from an actual populated V1 schema, not merely a fresh V1+V2 install.
 * A historical payment remains unassigned and inaccessible to scoped reads.
 */
@Testcontainers
class PaymentMigrationUpgradeIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Test
    void upgradesPopulatedV1WithoutReassigningOrDeletingLegacyPayments() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("1").load().migrate();

        UUID legacyId = UUID.randomUUID();
        String key = "legacy-" + UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("""
                INSERT INTO payments
                    (id, idempotency_key, amount, currency, merchant_reference, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, legacyId);
            statement.setString(2, key);
            statement.setBigDecimal(3, new BigDecimal("10.00"));
            statement.setString(4, "EUR");
            statement.setString(5, "legacy-reference");
            statement.setString(6, "RECEIVED");
            assertEquals(1, statement.executeUpdate());
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("2").load().migrate();

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var existing = connection.prepareStatement("""
                    SELECT merchant_id FROM payments WHERE id = ?
                    """);
             var count = connection.prepareStatement("""
                    SELECT COUNT(*) FROM payments
                    WHERE idempotency_key = ? AND merchant_id IS NULL
                    """);
             var scoped = connection.prepareStatement("""
                    SELECT COUNT(*) FROM payments
                    WHERE id = ? AND merchant_id = ?
                    """)) {
            existing.setObject(1, legacyId);
            try (var rows = existing.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getString(1), "V1 rows must not gain invented merchant ownership");
            }
            count.setString(1, key);
            try (var rows = count.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1L, rows.getLong(1));
            }
            scoped.setObject(1, legacyId);
            scoped.setString(2, "merchant-a");
            try (var rows = scoped.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0L, rows.getLong(1));
            }
        }

        // The new unique index permits identical keys in different merchant namespaces.
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var insert = connection.prepareStatement("""
                    INSERT INTO payments
                        (id, merchant_id, idempotency_key, amount, currency,
                         merchant_reference, status, created_at)
                    VALUES (?, ?, ?, 10.00, 'EUR', 'new-reference', 'RECEIVED', CURRENT_TIMESTAMP)
                    """)) {
            for (String merchant : new String[] {"merchant-a", "merchant-b"}) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, merchant);
                insert.setString(3, key);
                assertEquals(1, insert.executeUpdate());
            }
        }
    }
}
