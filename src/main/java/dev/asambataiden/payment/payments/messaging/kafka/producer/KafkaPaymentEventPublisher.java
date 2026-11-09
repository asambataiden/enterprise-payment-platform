package dev.asambataiden.payment.payments.messaging.kafka.producer;
import dev.asambataiden.payment.payments.outbox.PaymentEventPublisher;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * An opt-in Kafka adapter. Broker acknowledgment precedes outbox acknowledgment.
 * Message key is tenant/payment scoped for per-payment partition ordering.
 * This does not provide cross-resource exactly-once transactions.
 */
public final class KafkaPaymentEventPublisher implements PaymentEventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String topic;

    public KafkaPaymentEventPublisher(KafkaTemplate<String, String> kafka, String topic) {
        this.kafka = Objects.requireNonNull(kafka);
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Invalid Kafka topic");
        }
        this.topic = topic;
        this.json = JsonMapper.builder().build();
    }

    @Override
    public void publish(PaymentOutboxEvent event) throws Exception {
        Objects.requireNonNull(event, "event");
        String key = event.merchantId() + ":" + event.paymentId();
        String payload = json.writeValueAsString(event);
        try {
            kafka.send(topic, key, payload).get(8, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }
}
