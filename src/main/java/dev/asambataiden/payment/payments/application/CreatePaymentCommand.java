package dev.asambataiden.payment.payments.application;

import java.math.BigDecimal;

/** Validated payment values passed from the transport adapter. */
public record CreatePaymentCommand(
        BigDecimal amount,
        String currency,
        String merchantReference) {
}
