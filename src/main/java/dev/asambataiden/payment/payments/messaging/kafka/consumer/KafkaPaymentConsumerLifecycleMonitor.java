package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Low-cardinality lifecycle observation for the opt-in listener only.
 *
 * 0 = listener container missing, 1 = registered but stopped, 2 = running.
 * This is not a replacement for consumer-group lag or broker connectivity
 * monitoring, nor an automatic recovery/restart mechanism.
 */
public final class KafkaPaymentConsumerLifecycleMonitor {

    public static final String LISTENER_ID = "simulated-payment-projection-v1";

    public enum State {
        MISSING(0), STOPPED(1), RUNNING(2);

        private final int metricValue;

        State(int metricValue) {
            this.metricValue = metricValue;
        }

        public int metricValue() {
            return metricValue;
        }
    }

    private final KafkaListenerEndpointRegistry registry;

    public KafkaPaymentConsumerLifecycleMonitor(
            KafkaListenerEndpointRegistry registry, MeterRegistry meters) {
        this.registry = Objects.requireNonNull(registry);
        Objects.requireNonNull(meters);
        Gauge.builder("payment.kafka.consumer.lifecycle.state", this,
                        monitor -> monitor.state().metricValue())
                .description("Opt-in simulated payment listener: 0 missing, 1 stopped, 2 running")
                .register(meters);
    }

    public State state() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        if (container == null) {
            return State.MISSING;
        }
        return container.isRunning() ? State.RUNNING : State.STOPPED;
    }
}
