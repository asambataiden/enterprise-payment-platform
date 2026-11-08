package dev.asambataiden.payment.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentServiceTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentJdbcWriter writer = mock(PaymentJdbcWriter.class);
    private final PaymentService service = new PaymentService(repository, writer);

    @Test
    void repeatedRequestReturnsExistingPayment() {
        Payment saved = new Payment("test-key", new BigDecimal("10.00"), "EUR", "order-123");
        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(saved));

        PaymentResponse result = service.create("test-key",
                new CreatePaymentRequest(new BigDecimal("10.00"), "EUR", "order-123"));

        assertEquals(saved.getId(), result.id());
        verify(writer).insertIfAbsent(any(Payment.class));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reuseWithDifferentPayloadIsConflict() {
        Payment saved = new Payment("test-key", new BigDecimal("10.00"), "EUR", "order-123");
        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(saved));

        assertThrows(ResponseStatusException.class, () -> service.create("test-key",
                new CreatePaymentRequest(new BigDecimal("15.00"), "EUR", "order-123")));
    }

    @Test
    void newRequestCreatesReceivedRecord() {
        when(repository.findByIdempotencyKey("fresh")).thenAnswer(invocation ->
                Optional.of(new Payment("fresh", new BigDecimal("10.00"), "EUR", "order-123")));

        var response = service.create("fresh",
                new CreatePaymentRequest(new BigDecimal("10.00"), "EUR", "order-123"));

        assertEquals("RECEIVED", response.status());
        verify(writer).insertIfAbsent(any(Payment.class));
        verify(repository, never()).saveAndFlush(any());
    }
}
