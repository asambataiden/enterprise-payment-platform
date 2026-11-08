package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.infrastructure.persistence.Payment;
import dev.asambataiden.payment.payments.infrastructure.persistence.PaymentRepository;
import dev.asambataiden.payment.payments.infrastructure.persistence.PaymentJdbcWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;

class PaymentServiceTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentJdbcWriter writer = mock(PaymentJdbcWriter.class);
    private final PaymentService service = new PaymentService(repository, writer);

    @Test
    void repeatedRequestReturnsExistingPayment() {
        Payment saved = new Payment("test-key", new BigDecimal("10.00"), "EUR", "order-123");
        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(saved));

        PaymentResult result = service.create("test-key",
                new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-123"));

        assertEquals(saved.getId(), result.id());
        verify(writer).insertIfAbsent(any(Payment.class));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reuseWithDifferentPayloadIsConflict() {
        Payment saved = new Payment("test-key", new BigDecimal("10.00"), "EUR", "order-123");
        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(saved));

        assertThrows(IdempotencyConflictException.class, () -> service.create("test-key",
                new CreatePaymentCommand(new BigDecimal("15.00"), "EUR", "order-123")));
    }

    @Test
    void newRequestCreatesReceivedRecord() {
        when(repository.findByIdempotencyKey("fresh")).thenAnswer(invocation ->
                Optional.of(new Payment("fresh", new BigDecimal("10.00"), "EUR", "order-123")));

        var response = service.create("fresh",
                new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "order-123"));

        assertEquals("RECEIVED", response.status());
        verify(writer).insertIfAbsent(any(Payment.class));
        verify(repository, never()).saveAndFlush(any());
    }
}
