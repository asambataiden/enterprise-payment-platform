package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in real-broker least-privilege tests. Lab identities are distinct from
 * reader/admin credentials, and no real payment events are sent.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_SECURE_KAFKA_TEST_ENABLED", matches = "true")
class KafkaPrincipalAuthorizationE2E {

    private static final String STREAM = "payment.security.stream";
    private static final String FORBIDDEN_TOPIC = "payment.security.denied";
    private static final String APPROVED_GROUP = "payment-security-approved";
    private static final String FORBIDDEN_GROUP = "payment-security-forbidden";

    private static Map<String, Object> identity(String name) {
        var config = new HashMap<>(SecuredKafkaBrokerE2E.clientProperties());
        config.put("ssl.keystore.location", SecuredKafkaBrokerE2E.required(
                "PAYMENT_SECURE_KAFKA_" + name + "_KEYSTORE_LOCATION"));
        return config;
    }

    private static Map<String, Object> producerSettings() {
        var config = identity("PRODUCER");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // No transactional.id or IdempotentWrite ACL is granted in this training lab.
        // Disabling idempotence avoids introducing a second authorization scope.
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        return config;
    }

    private static Map<String, Object> consumerSettings(String group) {
        var config = identity("CONSUMER");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 7000);
        return config;
    }

    @Test
    void dedicatedProducerCanWriteToAuthorizedStream() throws Exception {
        String key = "probe-" + UUID.randomUUID();
        try (var producer = new KafkaProducer<String, String>(producerSettings())) {
            var metadata = producer.send(new ProducerRecord<>(STREAM, key, "nonfinancial-test-payload"))
                    .get(15, TimeUnit.SECONDS);
            assertEquals(STREAM, metadata.topic());
        }
    }

    @Test
    void dedicatedProducerCannotWriteToOtherTopic() throws Exception {
        try (var producer = new KafkaProducer<String, String>(producerSettings())) {
            var thrown = assertThrows(ExecutionException.class,
                    () -> producer.send(new ProducerRecord<>(
                                    FORBIDDEN_TOPIC, "denied", "nonfinancial-test-payload"))
                            .get(15, TimeUnit.SECONDS));
            assertInstanceOf(TopicAuthorizationException.class, thrown.getCause(),
                    "Broker must reject Write with a topic authorization error");
        }
    }

    @Test
    void dedicatedConsumerCanReadStreamUsingApprovedGroup() throws Exception {
        String marker = "probe-" + UUID.randomUUID();
        try (var producer = new KafkaProducer<String, String>(producerSettings())) {
            producer.send(new ProducerRecord<>(STREAM, marker, "nonfinancial-test-payload"))
                    .get(15, TimeUnit.SECONDS);
        }

        boolean observed = false;
        try (var consumer = new KafkaConsumer<String, String>(consumerSettings(APPROVED_GROUP))) {
            consumer.subscribe(java.util.List.of(STREAM));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!observed && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (marker.equals(record.key())) {
                        observed = true;
                        break;
                    }
                }
            }
        }
        assertTrue(observed, "Approved group must retrieve the produced marker record");
    }

    @Test
    void dedicatedConsumerCannotJoinUnauthorizedGroup() {
        try (var consumer = new KafkaConsumer<String, String>(consumerSettings(FORBIDDEN_GROUP))) {
            consumer.subscribe(java.util.List.of(STREAM));
            assertThrows(GroupAuthorizationException.class,
                    () -> consumer.poll(Duration.ofSeconds(8)),
                    "Broker must explicitly reject the unapproved consumer group");
        }
    }
}
