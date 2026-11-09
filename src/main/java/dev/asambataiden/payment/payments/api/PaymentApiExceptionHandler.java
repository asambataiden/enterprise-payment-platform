package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;
import jakarta.validation.ConstraintViolationException;
import dev.asambataiden.payment.payments.api.error.PaymentApiErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API error boundary. Never return exception class names, database
 * messages, stack traces, or internal infrastructure details to clients.
 */
@RestControllerAdvice
public class PaymentApiExceptionHandler {

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
        return ResponseEntity.status(status).body(detail);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> knownHttpError(ResponseStatusException exception) {
        var status = exception.getStatusCode();
        var detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.value() == 403 ? "Forbidden" :
                status.value() == 409 ? "Conflict" :
                status.value() == 404 ? "Not Found" : "Bad Request");
        detail.setDetail(exception.getReason());
        var code = status.value() == 403 ? PaymentApiErrorCode.MERCHANT_IDENTITY_REQUIRED
                : PaymentApiErrorCode.BAD_REQUEST;
        detail.setType(code.type());
        detail.setProperty("code", code.value());
        return ResponseEntity.status(status).body(detail);
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MissingRequestHeaderException.class
    })
    ResponseEntity<ProblemDetail> invalidRequest(Exception exception) {
        var detail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        detail.setTitle("Bad Request");
        detail.setDetail("The request failed validation.");
        detail.setType(PaymentApiErrorCode.VALIDATION_FAILED.type());
        detail.setProperty("code", PaymentApiErrorCode.VALIDATION_FAILED.value());
        return ResponseEntity.badRequest().body(detail);
    }
}
