package dev.asambataiden.payment.payments.outbox;

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

    public OptInKafkaPaymentListener(LocalKafkaPaymentRecordProcessor processor,
                                     LocalPaymentEventProjection projection) {
        this.processor = Objects.requireNonNull(processor);
        this.projection = Objects.requireNonNull(projection);
    }

    @KafkaListener(id = "simulated-payment-projection-v1",
            topics = "${payment.messaging.kafka.topic}",
            groupId = "${payment.messaging.kafka.consumer.group-id}",
            containerFactory = "paymentManualAckListenerFactory",
            autoStartup = "${payment.messaging.kafka.consumer.enabled:false}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        processor.processWithEvent("simulated-projection-v1", record, projection::record);
        Objects.requireNonNull(acknowledgment, "MANUAL_IMMEDIATE acknowledgment required").acknowledge();
    }
}
