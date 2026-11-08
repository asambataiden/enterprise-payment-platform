package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentApiExceptionHandlerTest {

    private final PaymentApiExceptionHandler handler = new PaymentApiExceptionHandler();

    @Test
    void idempotencyConflictReturnsProblemDetails() {
        var response = handler.idempotencyConflict(new IdempotencyConflictException());

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("Conflict", response.getBody().getTitle());
        assertEquals("Idempotency key already used with different request",
                response.getBody().getDetail());
    }

    @Test
    void missingPaymentReturnsNotFoundProblem() {
        var response = handler.paymentNotFound(new PaymentNotFoundException(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
    }
}
