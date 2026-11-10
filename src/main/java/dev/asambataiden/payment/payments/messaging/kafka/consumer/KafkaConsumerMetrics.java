package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;

/**
 * Low-cardinality consumer outcomes. NEVER use merchant, event, topic, partition,
 * offset, message or exception text as tags: they cause cardinality explosions
 * and may expose sensitive data.
 */
public final class KafkaConsumerMetrics {
    private final Counter processed;
    private final Counter replayed;
    private final Counter invalidContract;
    private final Counter untrustedEvent;
    private final Counter processingFailure;

    public KafkaConsumerMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry);
        processed = counter(registry, "processed");
        replayed = counter(registry, "replayed");
        invalidContract = counter(registry, "invalid_contract");
        untrustedEvent = counter(registry, "untrusted_event");
        processingFailure = counter(registry, "processing_failure");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("payment.kafka.consumer.records")
                .description("Simulated payment Kafka consumer records by bounded outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    public void success(boolean newlyProcessed) {
        (newlyProcessed ? processed : replayed).increment();
    }

    public void failure(String category) {
        switch (category) {
            case "INVALID_CONTRACT" -> invalidContract.increment();
            case "UNTRUSTED_EVENT" -> untrustedEvent.increment();
            case "PROCESSING_FAILURE" -> processingFailure.increment();
            default -> throw new IllegalArgumentException("Unsupported outcome");
        }
    }
}
