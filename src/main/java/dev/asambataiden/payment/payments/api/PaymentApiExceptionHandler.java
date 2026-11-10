package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;
import dev.asambataiden.payment.payments.domain.exception.PaymentTransitionConflictException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import dev.asambataiden.payment.shared.observability.CorrelationIdFilter;
import jakarta.validation.ConstraintViolationException;
import dev.asambataiden.payment.payments.api.error.PaymentApiErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API error boundary. Never return exception class names, database
 * messages, stack traces, or internal infrastructure details to clients.
 */
@RestControllerAdvice
public class PaymentApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentApiExceptionHandler.class);

    @ExceptionHandler(PaymentTransitionConflictException.class)
    ResponseEntity<ProblemDetail> transitionConflict(PaymentTransitionConflictException exception) {
        return domainError(HttpStatus.CONFLICT, "Conflict", "Simulated payment transition rejected",
                PaymentApiErrorCode.TRANSITION_CONFLICT);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessResourceFailureException exception) {
        log.error("Database resource failure; correlationId={}", MDC.get(CorrelationIdFilter.MDC_KEY), exception);
        var response = domainError(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "A required service is temporarily unavailable.", PaymentApiErrorCode.SERVICE_UNAVAILABLE);
        response.getBody().setProperty("retryable", true);
        return response;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        log.error("Unhandled API exception; correlationId={}", MDC.get(CorrelationIdFilter.MDC_KEY), exception);
        return domainError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.", PaymentApiErrorCode.INTERNAL_ERROR);
    }


    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> idempotencyConflict(IdempotencyConflictException exception) {
        return domainError(HttpStatus.CONFLICT, "Conflict", exception.getMessage(), PaymentApiErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ProblemDetail> paymentNotFound(PaymentNotFoundException exception) {
        return domainError(HttpStatus.NOT_FOUND, "Not Found", exception.getMessage(), PaymentApiErrorCode.PAYMENT_NOT_FOUND);
    }

    private ResponseEntity<ProblemDetail> domainError(HttpStatus status, String title, String message, PaymentApiErrorCode code) {
        var detail = ProblemDetail.forStatus(status);
        detail.setTitle(title);
        detail.setDetail(message);
        detail.setType(code.type());
        detail.setProperty("code", code.value());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            detail.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(status).body(detail);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> knownHttpError(ResponseStatusException exception) {
        int value = exception.getStatusCode().value();
        return switch (value) {
            case 400 -> domainError(HttpStatus.BAD_REQUEST, "Bad Request",
                    "Invalid payment request.", PaymentApiErrorCode.BAD_REQUEST);
            case 403 -> domainError(HttpStatus.FORBIDDEN, "Forbidden",
                    "Merchant identity is required.", PaymentApiErrorCode.MERCHANT_IDENTITY_REQUIRED);
            case 404 -> domainError(HttpStatus.NOT_FOUND, "Not Found",
                    "Payment not found.", PaymentApiErrorCode.PAYMENT_NOT_FOUND);
            case 409 -> domainError(HttpStatus.CONFLICT, "Conflict",
                    "Simulated payment transition rejected", PaymentApiErrorCode.TRANSITION_CONFLICT);
            default -> {
                if (value >= 500) {
                    yield unexpected(exception);
                }
                yield domainError(HttpStatus.BAD_REQUEST, "Bad Request",
                        "Invalid payment request.", PaymentApiErrorCode.BAD_REQUEST);
            }
        };
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Bad Request");
        detail.setDetail("The request failed validation.");
        detail.setType(PaymentApiErrorCode.VALIDATION_FAILED.type());
        detail.setProperty("code", PaymentApiErrorCode.VALIDATION_FAILED.value());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            detail.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.badRequest().body(detail);
    }
}
