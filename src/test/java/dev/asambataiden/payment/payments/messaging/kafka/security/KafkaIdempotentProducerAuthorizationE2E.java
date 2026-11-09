package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External secured broker: distinguish topic Write from cluster IdempotentWrite.
 * This checks authorization and client configuration; it does not simulate
 * dropped produce acknowledgements or establish end-to-end exactly-once effects.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_SECURE_KAFKA_TEST_ENABLED", matches = "true")
class KafkaIdempotentProducerAuthorizationE2E {

    private static final String STREAM = "payment.security.stream";
    private static final String DENIED = "payment.security.denied";

    private static Map<String, Object> settings(String keystoreEnvName) {
        var config = new HashMap<>(SecuredKafkaBrokerE2E.clientProperties());
        config.put("ssl.keystore.location", SecuredKafkaBrokerE2E.required(keystoreEnvName));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 12000);
        return config;
    }

    private static void assertHasCause(Throwable error, Class<? extends Throwable> expected) {
        Throwable cursor = error;
        while (cursor != null) {
            if (expected.isInstance(cursor)) {
                return;
            }
            cursor = cursor.getCause();
        }
        fail("Expected " + expected.getSimpleName()
                + " in broker authorization failure chain, got: " + error.getClass().getName());
    }

    @Test
    void idempotentProducerWithExplicitClusterGrantCanPublish() throws Exception {
        try (var producer = new KafkaProducer<String, String>(settings(
                "PAYMENT_SECURE_KAFKA_IDEMPOTENT_PRODUCER_KEYSTORE_LOCATION"))) {
            var record = new ProducerRecord<>(STREAM, "idempotent-" + UUID.randomUUID(),
                    "nonfinancial-idempotence-probe");
            var metadata = producer.send(record).get(20, TimeUnit.SECONDS);
            assertEquals(STREAM, metadata.topic());
            assertTrue(metadata.offset() >= 0, "Broker must acknowledge persisted record");
        }
    }

    @Test
    void topicWriteAloneCannotAuthorizeIdempotentProducerId() {
        try (var producer = new KafkaProducer<String, String>(settings(
                "PAYMENT_SECURE_KAFKA_PRODUCER_KEYSTORE_LOCATION"))) {
            var failure = assertThrows(Exception.class,
                    () -> producer.send(new ProducerRecord<>(
                                    STREAM, "missing-idempotent-grant", "nonfinancial-denied-probe"))
                            .get(20, TimeUnit.SECONDS));
            assertHasCause(failure, ClusterAuthorizationException.class);
        }
    }

    @Test
    void idempotentProducerWithClusterGrantStillCannotWriteOtherTopic() {
        try (var producer = new KafkaProducer<String, String>(settings(
                "PAYMENT_SECURE_KAFKA_IDEMPOTENT_PRODUCER_KEYSTORE_LOCATION"))) {
            var failure = assertThrows(Exception.class,
                    () -> producer.send(new ProducerRecord<>(
                                    DENIED, "wrong-topic", "nonfinancial-denied-probe"))
                            .get(20, TimeUnit.SECONDS));
            assertHasCause(failure, TopicAuthorizationException.class);
        }
    }
}
