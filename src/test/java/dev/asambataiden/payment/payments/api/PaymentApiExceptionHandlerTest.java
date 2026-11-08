package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.infrastructure.persistence.Payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PaymentApiExceptionHandlerTest {

    private final PaymentApiExceptionHandler handler = new PaymentApiExceptionHandler();

    @Test
    void idempotencyConflictReturnsProblemDetails() {
        var response = handler.knownHttpError(new ResponseStatusException(
                HttpStatus.CONFLICT, "Idempotency key already used with different request"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(409, response.getBody().getStatus());
        assertEquals("Conflict", response.getBody().getTitle());
        assertEquals("Idempotency key already used with different request",
                response.getBody().getDetail());
    }

    @Test
    void missingPaymentReturnsNotFoundProblem() {
        var response = handler.knownHttpError(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
    }
}
