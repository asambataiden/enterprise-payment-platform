package dev.asambataiden.payment.payments.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Actual Kafka broker, never a mocked KafkaTemplate. */
@Testcontainers
class KafkaPaymentEventPublisherIT {
    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.2");

    @Test
    void brokerAcknowledgesVersionedEventsWithStablePaymentPartitionKeyAndOrder() throws Exception {
        String topic = "payment.simulated.events.v1";
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
        }

        Map<String, Object> producerSettings = new HashMap<>();
        producerSettings.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerSettings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerSettings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerSettings.put(ProducerConfig.ACKS_CONFIG, "all");
        producerSettings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        var factory = new DefaultKafkaProducerFactory<String, String>(producerSettings);
        try {
            var publisher = new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic);
            UUID paymentId = UUID.randomUUID();
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            publisher.publish(new PaymentOutboxEvent(1, firstId, "merchant-a", paymentId, "AUTHORIZED"));
            publisher.publish(new PaymentOutboxEvent(1, secondId, "merchant-a", paymentId, "CAPTURED"));

            Map<String, Object> consumerSettings = new HashMap<>();
            consumerSettings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            consumerSettings.put(ConsumerConfig.GROUP_ID_CONFIG, "kafka-it-" + UUID.randomUUID());
            consumerSettings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerSettings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerSettings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerSettings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (var consumer = new KafkaConsumer<String, String>(consumerSettings)) {
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
        var factory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        try {
            assertThrows(IllegalArgumentException.class, () ->
                    new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), "invalid topic"));
        } finally {
            factory.destroy();
        }
    }
}
