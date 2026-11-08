package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.application.port.PaymentStore;
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentServiceTest {
    private final PaymentStore store = mock(PaymentStore.class);
    private final PaymentService service = new PaymentService(store);

    private static PaymentResult payment(BigDecimal amount) {
        return new PaymentResult(
                UUID.randomUUID(), amount, "EUR", "order-123",
                "RECEIVED", Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void repeatedRequestReturnsExistingPayment() {
        var command = new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-123");
        var stored = payment(new BigDecimal("10.00"));
        when(store.insertIfAbsentAndFind("test-key", command)).thenReturn(stored);

        var actual = service.create("test-key", command);

        assertEquals(stored.id(), actual.id());
        verify(store).insertIfAbsentAndFind("test-key", command);
    }

    @Test
    void reuseWithDifferentPayloadIsConflict() {
        var command = new CreatePaymentCommand(new BigDecimal("15.00"), "EUR", "order-123");
        when(store.insertIfAbsentAndFind("test-key", command))
                .thenReturn(payment(new BigDecimal("10.00")));

        assertThrows(IdempotencyConflictException.class,
                () -> service.create("test-key", command));
    }

    @Test
    void newRequestCreatesReceivedRecord() {
        var command = new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-123");
        when(store.insertIfAbsentAndFind("fresh", command))
                .thenReturn(payment(new BigDecimal("10.00")));

        var result = service.create("fresh", command);

        assertEquals("RECEIVED", result.status());
        verify(store).insertIfAbsentAndFind("fresh", command);
    }

    @Test
    void unknownPaymentThrowsDomainException() {
        UUID id = UUID.randomUUID();
        when(store.findById(id)).thenReturn(Optional.empty());

        assertThrows(PaymentNotFoundException.class, () -> service.get(id));
    }

    @Test
    void reusedKeyWithDifferentCurrencyIsRejected() {
        var command = new CreatePaymentCommand(new BigDecimal("10.00"), "USD", "order-123");
        when(store.insertIfAbsentAndFind("same-key", command))
                .thenReturn(payment(new BigDecimal("10.00")));

        assertThrows(IdempotencyConflictException.class,
                () -> service.create("same-key", command));
    }

    @Test
    void reusedKeyWithDifferentMerchantReferenceIsRejected() {
        var command = new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-changed");
        when(store.insertIfAbsentAndFind("same-key", command))
                .thenReturn(payment(new BigDecimal("10.00")));

        assertThrows(IdempotencyConflictException.class,
                () -> service.create("same-key", command));
    }

    @Test
    void equivalentDecimalAmountsRemainIdempotent() {
        var command = new CreatePaymentCommand(new BigDecimal("10.0"), "EUR", "order-123");
        var stored = payment(new BigDecimal("10.00"));
        when(store.insertIfAbsentAndFind("same-key", command)).thenReturn(stored);

        assertEquals(stored.id(), service.create("same-key", command).id());
    }
}
