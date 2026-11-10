package dev.asambataiden.payment.payments.domain;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentStateMachineTest {
    @Test
    void allowsExplicitLifecycleTransitions() {
        assertEquals(PaymentState.AUTHORIZED,
                PaymentStateMachine.transition(PaymentState.RECEIVED, PaymentState.AUTHORIZED));
        assertEquals(PaymentState.CAPTURED,
                PaymentStateMachine.transition(PaymentState.AUTHORIZED, PaymentState.CAPTURED));
        assertEquals(PaymentState.REFUNDED,
                PaymentStateMachine.transition(PaymentState.CAPTURED, PaymentState.REFUNDED));
    }

    @Test
    void permitsRejectionOrCancellationBeforeCapture() {
        assertEquals(PaymentState.FAILED,
                PaymentStateMachine.transition(PaymentState.RECEIVED, PaymentState.FAILED));
        assertEquals(PaymentState.CANCELLED,
                PaymentStateMachine.transition(PaymentState.AUTHORIZED, PaymentState.CANCELLED));
    }

    @Test
    void rejectsSkippingAuthorization() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.transition(PaymentState.RECEIVED, PaymentState.CAPTURED));
    }

    @Test
    void rejectsRefundBeforeCaptureAndTransitionOutOfTerminalState() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.transition(PaymentState.AUTHORIZED, PaymentState.REFUNDED));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.transition(PaymentState.REFUNDED, PaymentState.CAPTURED));
    }

    /**
     * Exhaustive safety net: checks every pair rather than a few example flows.
     * A future lifecycle extension must explicitly update this independent
     * contract, making a business-rule change visible in code review.
     */
    @Test
    void everyStateTransitionMatchesTheApprovedBusinessMatrix() {
        Map<PaymentState, Set<PaymentState>> expected = Map.of(
                PaymentState.RECEIVED, Set.of(
                        PaymentState.AUTHORIZED, PaymentState.FAILED, PaymentState.CANCELLED),
                PaymentState.AUTHORIZED, Set.of(
                        PaymentState.CAPTURED, PaymentState.FAILED, PaymentState.CANCELLED),
                PaymentState.CAPTURED, Set.of(PaymentState.REFUNDED),
                PaymentState.FAILED, Set.of(),
                PaymentState.CANCELLED, Set.of(),
                PaymentState.REFUNDED, Set.of()
        );
        assertEquals(Set.of(PaymentState.values()), expected.keySet(),
                "The business matrix must explicitly cover every lifecycle state");

        for (PaymentState current : PaymentState.values()) {
            for (PaymentState target : PaymentState.values()) {
                if (expected.get(current).contains(target)) {
                    assertEquals(target, PaymentStateMachine.transition(current, target),
                            () -> "Approved transition: " + current + " -> " + target);
                } else {
                    assertThrows(IllegalStateException.class,
                            () -> PaymentStateMachine.transition(current, target),
                            () -> "Forbidden transition: " + current + " -> " + target);
                }
            }
        }
    }

    @Test
    void rejectsNullAndSameStateTransitions() {
        assertThrows(IllegalArgumentException.class,
                () -> PaymentStateMachine.transition(null, PaymentState.CAPTURED));
        assertThrows(IllegalArgumentException.class,
                () -> PaymentStateMachine.transition(PaymentState.RECEIVED, null));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.transition(PaymentState.RECEIVED, PaymentState.RECEIVED));
    }
}
