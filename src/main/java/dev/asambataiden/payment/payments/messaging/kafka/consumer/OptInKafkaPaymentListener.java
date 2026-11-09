package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Opt-in simulated, SAME-DATABASE event projection listener.
 * A manual ACK follows a successful PostgreSQL inbox + projection commit.
 * Failure propagates to the stopping error handler; no offset is intentionally
 * acknowledged or poison record silently skipped.
 */
public final class OptInKafkaPaymentListener {
    private final LocalKafkaPaymentRecordProcessor processor;
    private final LocalPaymentEventProjection projection;
    private final KafkaConsumerFailureObservations observations;
    private final KafkaConsumerMetrics metrics;

    public OptInKafkaPaymentListener(LocalKafkaPaymentRecordProcessor processor,
                                     LocalPaymentEventProjection projection,
                                     KafkaConsumerFailureObservations observations,
                                     KafkaConsumerMetrics metrics) {
        this.processor = Objects.requireNonNull(processor);
        this.projection = Objects.requireNonNull(projection);
        this.observations = Objects.requireNonNull(observations);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @KafkaListener(id = "simulated-payment-projection-v1",
            topics = "${payment.messaging.kafka.topic}",
            groupId = "${payment.messaging.kafka.consumer.group-id}",
            containerFactory = "paymentManualAckListenerFactory",
            autoStartup = "${payment.messaging.kafka.consumer.enabled:false}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        boolean newlyProcessed;
        try {
            newlyProcessed = processor.processWithEvent("simulated-projection-v1", record, projection::record);
        } catch (RuntimeException failure) {
            String category = failure instanceof SecurityException ? "UNTRUSTED_EVENT"
                    : failure instanceof IllegalArgumentException ? "INVALID_CONTRACT"
                    : "PROCESSING_FAILURE";
            try {
                observations.record("simulated-projection-v1", record, category);
            } catch (RuntimeException observationFailure) {
                failure.addSuppressed(observationFailure);
            }
            metrics.failure(category);
            // Deliberately do not acknowledge a failed record. The container
            // stopping error handler halts the partition for operator review.
            throw failure;
        }
        Objects.requireNonNull(acknowledgment, "MANUAL_IMMEDIATE acknowledgment required").acknowledge();
        metrics.success(newlyProcessed);
    }
}
