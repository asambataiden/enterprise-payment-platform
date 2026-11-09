package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaPaymentConsumerLifecycleMonitorTest {

    @Test
    void reportsMissingStoppedAndRunningWithoutDynamicMetricTags() {
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var listener = mock(MessageListenerContainer.class);
        var meters = new SimpleMeterRegistry();
        try {
            var monitor = new KafkaPaymentConsumerLifecycleMonitor(registry, meters);
            var gauge = meters.get("payment.kafka.consumer.lifecycle.state").gauge();

            assertEquals(KafkaPaymentConsumerLifecycleMonitor.State.MISSING,
                    monitor.state());
            assertEquals(0.0, gauge.value());

            when(registry.getListenerContainer(
                    KafkaPaymentConsumerLifecycleMonitor.LISTENER_ID))
                    .thenReturn(listener);
            when(listener.isRunning()).thenReturn(false);

            assertEquals(KafkaPaymentConsumerLifecycleMonitor.State.STOPPED,
                    monitor.state());
            assertEquals(1.0, gauge.value());

            when(listener.isRunning()).thenReturn(true);

            assertEquals(KafkaPaymentConsumerLifecycleMonitor.State.RUNNING,
                    monitor.state());
            assertEquals(2.0, gauge.value());
            assertTrue(gauge.getId().getTags().isEmpty());
        } finally {
            meters.close();
        }
    }
}
