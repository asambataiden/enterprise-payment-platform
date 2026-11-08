
package dev.asambataiden.payment.payment;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class PaymentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    PaymentService service;

    @Autowired
    PaymentRepository repository;

    @Test
    void createsAndRetrievesPersistedPayment() {
        String key = "integration-" + UUID.randomUUID();

        var request = new CreatePaymentRequest(
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

        var request = new CreatePaymentRequest(
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

        service.create(key, new CreatePaymentRequest(
            new BigDecimal("10.00"),
            "EUR",
            "order-conflict"
        ));

        assertThrows(ResponseStatusException.class,
            () -> service.create(key,
                new CreatePaymentRequest(
                    new BigDecimal("20.00"),
                    "EUR",
                    "order-conflict"
                )));
    }
}
