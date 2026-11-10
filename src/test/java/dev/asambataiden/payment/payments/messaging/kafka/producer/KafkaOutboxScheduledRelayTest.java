package dev.asambataiden.payment.payments.messaging.kafka.producer;

import dev.asambataiden.payment.payments.outbox.SupervisedPaymentOutboxWorker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class KafkaOutboxScheduledRelayTest {

    @Test
    void delegatesPollingToBoundedWorker() {
        var worker = mock(SupervisedPaymentOutboxWorker.class);
        when(worker.runCycle()).thenReturn(3);

        var relay =
                new KafkaOutboxSchedulingConfiguration
                        .KafkaOutboxScheduledRelay(worker);

        relay.poll();

        verify(worker, times(1)).runCycle();
    }

    @Test
    void continuesAfterSuccessfulEmptyCycle() {
        var worker = mock(SupervisedPaymentOutboxWorker.class);
        when(worker.runCycle()).thenReturn(0);

        var relay =
                new KafkaOutboxSchedulingConfiguration
                        .KafkaOutboxScheduledRelay(worker);

        relay.poll();
        relay.poll();

        verify(worker, times(2)).runCycle();
    }

    @Test
    void stopsPollingAfterUnexpectedCycleFailure() {
        var worker = mock(SupervisedPaymentOutboxWorker.class);
        when(worker.runCycle())
                .thenThrow(new IllegalStateException(
                        "Simulated unexpected worker failure"));

        var relay =
                new KafkaOutboxSchedulingConfiguration
                        .KafkaOutboxScheduledRelay(worker);

        assertDoesNotThrow(relay::poll);
        assertDoesNotThrow(relay::poll);

        // Fail-stop behavior: the second invocation must not
        // attempt another dispatch cycle.
        verify(worker, times(1)).runCycle();
    }
}
