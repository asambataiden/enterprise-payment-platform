package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.domain.PaymentState;
import jakarta.validation.constraints.NotNull;

/** Simulation-only command; monetary amount and account IDs are never supplied by the client. */
public record SimulatedTransitionRequest(@NotNull PaymentState targetState) {
}
