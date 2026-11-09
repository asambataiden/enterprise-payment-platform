package dev.asambataiden.payment.payments.api.error;

import java.net.URI;

/**
 * Public, stable API error vocabulary. Domain exceptions remain transport-free.
 * Never expose SQL states, exception class names or Kafka internal errors.
 */
public enum PaymentApiErrorCode {
    IDEMPOTENCY_CONFLICT("PAYMENT_IDEMPOTENCY_CONFLICT", "idempotency-conflict"),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", "payment-not-found"),
    VALIDATION_FAILED("PAYMENT_VALIDATION_FAILED", "validation-failed"),
    MERCHANT_IDENTITY_REQUIRED("MERCHANT_IDENTITY_REQUIRED", "merchant-identity-required"),
    BAD_REQUEST("PAYMENT_BAD_REQUEST", "bad-request");

    private static final String PREFIX = "urn:problem:enterprise-payment-platform:";
    private final String value;
    private final String slug;

    PaymentApiErrorCode(String value, String slug) {
        this.value = value;
        this.slug = slug;
    }

    public String value() {
        return value;
    }

    public URI type() {
        return URI.create(PREFIX + slug);
    }
}
