
package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.infrastructure.persistence.PaymentRepository;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class PaymentServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    PaymentService service;

    @Autowired
    PaymentRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsAndRetrievesPersistedPayment() {
        String key = "integration-" + UUID.randomUUID();

        var request = new CreatePaymentCommand(
            new BigDecimal("49.99"),
            "EUR",
            "order-integration"
        );

        var created = service.create("merchant-a", key, request);
        var retrieved = service.get("merchant-a", created.id());

        assertEquals(created.id(), retrieved.id());
        assertEquals("RECEIVED", retrieved.status());
        assertEquals(0,
            new BigDecimal("49.99")
                .compareTo(retrieved.amount()));
    }

    @Test
    void repeatedRequestDoesNotCreateDuplicate() {
        String key = "retry-" + UUID.randomUUID();

        var request = new CreatePaymentCommand(
            new BigDecimal("10.00"),
            "EUR",
            "order-retry"
        );

        var first = service.create("merchant-a", key, request);
        var second = service.create("merchant-a", key, request);

        assertEquals(first.id(), second.id());

        var persisted = repository.findByMerchantIdAndIdempotencyKey("merchant-a", key);
        assertTrue(persisted.isPresent());
        assertEquals(first.id(), persisted.orElseThrow().getId());
    }

    @Test
    void changedPayloadWithSameKeyIsRejected() {
        String key = "conflict-" + UUID.randomUUID();

        service.create("merchant-a", key, new CreatePaymentCommand(
            new BigDecimal("10.00"),
            "EUR",
            "order-conflict"
        ));

        assertThrows(IdempotencyConflictException.class,
            () -> service.create("merchant-a", key,
                new CreatePaymentCommand(
                    new BigDecimal("20.00"),
                    "EUR",
                    "order-conflict"
                )));
    }

    @Test
    void concurrentIdenticalRequestsProduceOnePayment() throws Exception {
        // Repeat with independent keys to exercise real concurrent transactions.
        for (int round = 0; round < 5; round++) {
            String key = "concurrent-" + UUID.randomUUID();
            var request = new CreatePaymentCommand(
                    new BigDecimal("29.99"), "EUR", "order-concurrent");
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> {
                    start.await();
                    return service.create("merchant-a", key, request).id();
                });
                var second = executor.submit(() -> {
                    start.await();
                    return service.create("merchant-a", key, request).id();
                });
                start.countDown();
                UUID firstId = first.get(15, TimeUnit.SECONDS);
                UUID secondId = second.get(15, TimeUnit.SECONDS);

                assertEquals(firstId, secondId, "Concurrent retries must resolve to the same payment");
                Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payments WHERE merchant_id = 'merchant-a' AND idempotency_key = ?",
                        Long.class, key);
                assertEquals(Long.valueOf(1L), count);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void changedCurrencyOrMerchantReferenceDoesNotCreateAnotherRow() {
        var original = new CreatePaymentCommand(
                new BigDecimal("10.00"), "EUR", "order-original");
        var variations = java.util.List.of(
                new CreatePaymentCommand(new BigDecimal("10.00"), "USD", "order-original"),
                new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-changed"));

        for (var changed : variations) {
            String key = "conflicting-field-" + UUID.randomUUID();
            var first = service.create("merchant-a", key, original);

            assertThrows(IdempotencyConflictException.class,
                    () -> service.create("merchant-a", key, changed));

            var persisted = repository.findByMerchantIdAndIdempotencyKey("merchant-a", key).orElseThrow();
            assertEquals(first.id(), persisted.getId());
            assertEquals("EUR", persisted.getCurrency());
            assertEquals("order-original", persisted.getMerchantReference());
            assertEquals(Long.valueOf(1L), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE merchant_id = 'merchant-a' AND idempotency_key = ?",
                    Long.class, key));
        }
    }

    @Test
    void decimalEquivalentRetryKeepsExistingPaymentId() {
        String key = "decimal-retry-" + UUID.randomUUID();
        var first = service.create("merchant-a", key, new CreatePaymentCommand(
                new BigDecimal("10.00"), "EUR", "order-decimal"));
        var second = service.create("merchant-a", key, new CreatePaymentCommand(
                new BigDecimal("10.0"), "EUR", "order-decimal"));

        assertEquals(first.id(), second.id());
        assertEquals(Long.valueOf(1L), jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE merchant_id = 'merchant-a' AND idempotency_key = ?",
                Long.class, key));
    }

    @Test
    void differentMerchantsCanReuseKeyWithoutSharingPayment() {
        String key = "multi-tenant-" + UUID.randomUUID();
        var request = new CreatePaymentCommand(new BigDecimal("7.00"), "EUR", "shared-order");

        var first = service.create("merchant-a", key, request);
        var second = service.create("merchant-b", key, request);

        assertNotEquals(first.id(), second.id());
        assertEquals(first.id(), service.get("merchant-a", first.id()).id());
        assertEquals(second.id(), service.get("merchant-b", second.id()).id());
        assertThrows(dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException.class,
                () -> service.get("merchant-b", first.id()));
        assertThrows(dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException.class,
                () -> service.get("merchant-a", second.id()));
        assertEquals(Long.valueOf(2L), jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?", Long.class, key));
    }

    @Test
    void conflictingRetriesAreScopedToOwningMerchant() {
        String key = "same-namespace-" + UUID.randomUUID();
        var first = service.create("merchant-a", key,
                new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-a"));
        var second = service.create("merchant-b", key,
                new CreatePaymentCommand(new BigDecimal("20.00"), "EUR", "order-b"));

        assertNotEquals(first.id(), second.id());
        assertThrows(IdempotencyConflictException.class,
                () -> service.create("merchant-a", key,
                        new CreatePaymentCommand(new BigDecimal("15.00"), "EUR", "order-a")));
        assertEquals(first.id(), service.get("merchant-a", first.id()).id());
        assertEquals(second.id(), service.get("merchant-b", second.id()).id());
    }

    @Test
    void legacyUnassignedPaymentCannotBeReadByAnyMerchant() {
        String key = "legacy-" + UUID.randomUUID();
        UUID legacyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments
                  (id, idempotency_key, amount, currency, merchant_reference, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, legacyId, key, new BigDecimal("11.00"), "EUR", "legacy", "RECEIVED");

        assertThrows(dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException.class,
                () -> service.get("merchant-a", legacyId));
        assertThrows(dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException.class,
                () -> service.get("merchant-b", legacyId));
    }
}
