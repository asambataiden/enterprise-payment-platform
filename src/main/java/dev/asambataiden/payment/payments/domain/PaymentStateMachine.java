package dev.asambataiden.payment.payments.domain;

import dev.asambataiden.payment.payments.domain.exception.PaymentTransitionConflictException;
import java.util.Map;
import java.util.Set;

/**
 * Explicit state-transition policy. The caller must additionally authorize
 * the event and make transitions atomic when persistence is introduced.
 */
public final class PaymentStateMachine {
    private static final Map<PaymentState, Set<PaymentState>> ALLOWED = Map.of(
            PaymentState.RECEIVED, Set.of(PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.CANCELLED),
            PaymentState.AUTHORIZED, Set.of(PaymentState.CAPTURED, PaymentState.FAILED, PaymentState.CANCELLED),
            PaymentState.CAPTURED, Set.of(PaymentState.REFUNDED),
            PaymentState.FAILED, Set.of(),
            PaymentState.CANCELLED, Set.of(),
            PaymentState.REFUNDED, Set.of()
    );

    private PaymentStateMachine() {}

    public static PaymentState transition(PaymentState current, PaymentState target) {
        if (current == null || target == null) {
            throw new IllegalArgumentException("Payment states must not be null");
        }
        if (!ALLOWED.get(current).contains(target)) {
            throw new PaymentTransitionConflictException("Invalid simulated payment transition: " + current + " -> " + target);
        }
        return target;
    }
}
