package dev.asambataiden.payment.payments.domain;

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
