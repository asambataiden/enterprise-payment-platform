package dev.asambataiden.payment.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentServiceTest {
    private final PaymentRepository repository = mock(PaymentRepository.class);
    private final PaymentService service = new PaymentService(repository);

    @Test
    void repeatedRequestReturnsExistingPayment() {
        Payment saved = new Payment("test-key", new BigDecimal("10.00"), "EUR", "order-123");
        when(repository.findByIdempotencyKey("test-key")).thenReturn(Optional.of(saved));
        PaymentResponse result = service.create("test-key", new CreatePaymentRequest(new BigDecimal("10.00"), "EUR", "order-123"));
        assertEquals(saved.getId(), result.id());
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
        when(repository.findByIdempotencyKey("fresh")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create("fresh", new CreatePaymentRequest(new BigDecimal("10.00"), "EUR", "order-123"));
        assertEquals("RECEIVED", response.status());
        verify(repository).saveAndFlush(any(Payment.class));
    }
}
