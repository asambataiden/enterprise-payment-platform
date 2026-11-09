package dev.asambataiden.payment.testinfra;

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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Test-scope Kafka factory: owns repeated broker/client setup, not test assertions.
 * Deliberately has no production dependency and exposes the broker configuration
 * so each integration test can choose its own topics and consumer behavior.
 */
public final class KafkaTestSupport {
    public static final String IMAGE = "apache/kafka:4.1.2";

    private KafkaTestSupport() {}

    public static KafkaContainer newBroker() {
        return new KafkaContainer(IMAGE);
    }

    public static void createTopic(KafkaContainer broker, String topic, int partitions) throws Exception {
        if (partitions < 1) {
            throw new IllegalArgumentException("Topic must have at least one partition");
        }
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
        }
    }

    public static DefaultKafkaProducerFactory<String, String> newProducerFactory(KafkaContainer broker) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(properties);
    }

    public static KafkaConsumer<String, String> newConsumer(KafkaContainer broker, String groupPrefix) {
        if (groupPrefix == null || groupPrefix.isBlank()) {
            throw new IllegalArgumentException("Group prefix is required");
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + "-" + UUID.randomUUID());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties);
    }
}
