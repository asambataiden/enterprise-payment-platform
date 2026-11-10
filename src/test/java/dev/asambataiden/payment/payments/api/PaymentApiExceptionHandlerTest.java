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
        assertEquals("PAYMENT_IDEMPOTENCY_CONFLICT", response.getBody().getProperties().get("code"));
        assertEquals("urn:problem:enterprise-payment-platform:idempotency-conflict", response.getBody().getType().toString());
        assertEquals("Idempotency key already used with different request",
                response.getBody().getDetail());
    }

    @Test
    void missingPaymentReturnsNotFoundProblem() {
        var response = handler.paymentNotFound(new PaymentNotFoundException(UUID.randomUUID()));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(404, response.getBody().getStatus());
        assertEquals("PAYMENT_NOT_FOUND", response.getBody().getProperties().get("code"));
    }
    @Test
    void merchantIdentityErrorIsForbiddenAndHasStableCode() {
        var response = handler.knownHttpError(new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN, "Merchant identity is required"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Forbidden", response.getBody().getTitle());
        assertEquals("MERCHANT_IDENTITY_REQUIRED", response.getBody().getProperties().get("code"));
    }

    @Test
    void databaseResourceOutageReturnsSanitized503WithRetryHint() {
        var result = handler.databaseUnavailable(
                new org.springframework.dao.DataAccessResourceFailureException(
                        "private-db-host:5432 connection refused"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.getStatusCode());
        assertEquals("SERVICE_UNAVAILABLE", result.getBody().getProperties().get("code"));
        assertEquals(Boolean.TRUE, result.getBody().getProperties().get("retryable"));
        assertEquals("A required service is temporarily unavailable.", result.getBody().getDetail());
    }

    @Test
    void unexpectedFailureReturnsSanitized500WithoutInternals() {
        var result = handler.unexpected(new IllegalStateException("SQL password=secret"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals("INTERNAL_ERROR", result.getBody().getProperties().get("code"));
        assertEquals("An unexpected error occurred.", result.getBody().getDetail());
    }

    @Test
    void transitionConflictDoesNotExposeDomainDiagnostic() {
        var result = handler.transitionConflict(
                new dev.asambataiden.payment.payments.domain.exception.PaymentTransitionConflictException(
                        "sensitive internal state"));
        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
        assertEquals("PAYMENT_TRANSITION_CONFLICT", result.getBody().getProperties().get("code"));
        assertEquals("Simulated payment transition rejected", result.getBody().getDetail());
    }

    @Test
    void badRequestErrorHasStableNonSensitiveCode() {
        var response = handler.knownHttpError(new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid idempotency header"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("PAYMENT_BAD_REQUEST", response.getBody().getProperties().get("code"));
    }
}
