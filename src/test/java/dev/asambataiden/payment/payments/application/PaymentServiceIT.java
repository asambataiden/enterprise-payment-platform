
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

        var created = service.create(key, request);
        var retrieved = service.get(created.id());

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

        var first = service.create(key, request);
        var second = service.create(key, request);

        assertEquals(first.id(), second.id());

        var persisted = repository.findByIdempotencyKey(key);
        assertTrue(persisted.isPresent());
        assertEquals(first.id(), persisted.orElseThrow().getId());
    }

    @Test
    void changedPayloadWithSameKeyIsRejected() {
        String key = "conflict-" + UUID.randomUUID();

        service.create(key, new CreatePaymentCommand(
            new BigDecimal("10.00"),
            "EUR",
            "order-conflict"
        ));

        assertThrows(IdempotencyConflictException.class,
            () -> service.create(key,
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
                    return service.create(key, request).id();
                });
                var second = executor.submit(() -> {
                    start.await();
                    return service.create(key, request).id();
                });
                start.countDown();
                UUID firstId = first.get(15, TimeUnit.SECONDS);
                UUID secondId = second.get(15, TimeUnit.SECONDS);

                assertEquals(firstId, secondId, "Concurrent retries must resolve to the same payment");
                Long count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
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
            var first = service.create(key, original);

            assertThrows(IdempotencyConflictException.class,
                    () -> service.create(key, changed));

            var persisted = repository.findByIdempotencyKey(key).orElseThrow();
            assertEquals(first.id(), persisted.getId());
            assertEquals("EUR", persisted.getCurrency());
            assertEquals("order-original", persisted.getMerchantReference());
            assertEquals(Long.valueOf(1L), jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                    Long.class, key));
        }
    }

    @Test
    void decimalEquivalentRetryKeepsExistingPaymentId() {
        String key = "decimal-retry-" + UUID.randomUUID();
        var first = service.create(key, new CreatePaymentCommand(
                new BigDecimal("10.00"), "EUR", "order-decimal"));
        var second = service.create(key, new CreatePaymentCommand(
                new BigDecimal("10.0"), "EUR", "order-decimal"));

        assertEquals(first.id(), second.id());
        assertEquals(Long.valueOf(1L), jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?",
                Long.class, key));
    }
}
