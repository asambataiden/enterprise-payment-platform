package dev.asambataiden.payment.payments.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Kafka broker, never a mocked KafkaTemplate. */
@Testcontainers
class KafkaPaymentEventPublisherIT {
    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @Test
    void brokerAcknowledgesVersionedEventsWithStablePaymentPartitionKeyAndOrder() throws Exception {
        String topic = "payment.simulated.events.v1";
        KafkaTestSupport.createTopic(kafka, topic, 2);

        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            var publisher = new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic);
            UUID paymentId = UUID.randomUUID();
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            publisher.publish(new PaymentOutboxEvent(1, firstId, "merchant-a", paymentId, "AUTHORIZED"));
            publisher.publish(new PaymentOutboxEvent(1, secondId, "merchant-a", paymentId, "CAPTURED"));

            try (var consumer = KafkaTestSupport.newConsumer(kafka, "kafka-it")) {
                consumer.subscribe(List.of(topic));
                var records = new ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>>();
                for (int poll = 0; poll < 8 && records.size() < 2; poll++) {
                    consumer.poll(Duration.ofSeconds(3)).forEach(records::add);
                }
                assertEquals(2, records.size(), "Both broker-ACKed messages must be observable");
                assertEquals("merchant-a:" + paymentId, records.get(0).key());
                assertEquals(records.get(0).partition(), records.get(1).partition(),
                        "Same payment must retain partition affinity");
                assertTrue(records.get(0).offset() < records.get(1).offset());
                var mapper = JsonMapper.builder().build();
                var first = mapper.readValue(records.get(0).value(), PaymentOutboxEvent.class);
                var second = mapper.readValue(records.get(1).value(), PaymentOutboxEvent.class);
                assertEquals(firstId, first.eventId());
                assertEquals(secondId, second.eventId());
                assertEquals("CAPTURED", second.targetState());
                assertEquals(1, second.schemaVersion());
            }
        } finally {
            factory.destroy();
        }
    }

    @Test
    void invalidTopicFailsBeforeAnyKafkaSend() {
        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), "invalid topic"));
        } finally {
            factory.destroy();
        }
    }
}
