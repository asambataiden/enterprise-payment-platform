package dev.asambataiden.payment.payments.domain;

/** Simulation-only lifecycle; RECEIVED does not imply money movement. */
public enum PaymentState {
    RECEIVED,
    AUTHORIZED,
    CAPTURED,
    FAILED,
    CANCELLED,
    REFUNDED
}
