package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-broker negative security checks. No real payment data is produced.
 * Test requires a separately bootstrapped, ACL-protected mTLS lab.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_SECURE_KAFKA_TEST_ENABLED", matches = "true")
class KafkaNegativeSecurityE2E {

    private static String allowedTopic() {
        return SecuredKafkaBrokerE2E.required("PAYMENT_SECURE_KAFKA_ALLOWED_TOPIC");
    }

    private static void assertHealthyBaseline(Map<String, Object> secureProperties) throws Exception {
        try (Admin admin = Admin.create(secureProperties)) {
            var topics = admin.describeTopics(java.util.List.of(allowedTopic()))
                    .allTopicNames().get(10, TimeUnit.SECONDS);
            assertTrue(topics.containsKey(allowedTopic()), "Baseline secure connection must succeed");
        }
    }

    private static void assertCannotDescribe(Map<String, Object> invalidProperties)
            throws Exception {
        try (Admin admin = Admin.create(invalidProperties)) {
            assertThrows(Exception.class, () ->
                    admin.describeTopics(java.util.List.of(allowedTopic()))
                            .allTopicNames().get(10, TimeUnit.SECONDS),
                    "Unauthenticated or untrusted client must not obtain topic metadata");
        }
    }

    @Test
    void missingClientCertificateCannotConnectToMutualTlsBroker() throws Exception {
        var secure = SecuredKafkaBrokerE2E.clientProperties();
        assertHealthyBaseline(secure);
        var noIdentity = new HashMap<>(secure);
        noIdentity.remove("ssl.keystore.location");
        noIdentity.remove("ssl.keystore.password");
        noIdentity.remove("ssl.key.password");
        assertCannotDescribe(noIdentity);
        assertHealthyBaseline(secure);
    }

    @Test
    void untrustedServerCertificateCannotBeAccepted() throws Exception {
        var secure = SecuredKafkaBrokerE2E.clientProperties();
        assertHealthyBaseline(secure);
        var wrongTrust = new HashMap<>(secure);
        wrongTrust.put("ssl.truststore.location",
                SecuredKafkaBrokerE2E.required("PAYMENT_SECURE_KAFKA_ROGUE_TRUSTSTORE_LOCATION"));
        wrongTrust.put("ssl.truststore.password",
                SecuredKafkaBrokerE2E.required("PAYMENT_SECURE_KAFKA_ROGUE_TRUSTSTORE_PASSWORD"));
        assertCannotDescribe(wrongTrust);
        assertHealthyBaseline(secure);
    }

    @Test
    void describeOnlyPrincipalCannotPublishToPermittedReadOnlyTopic() throws Exception {
        var secure = SecuredKafkaBrokerE2E.clientProperties();
        assertHealthyBaseline(secure);
        var producerSettings = new HashMap<String, Object>(secure);
        producerSettings.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerSettings.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerSettings.put(ProducerConfig.ACKS_CONFIG, "all");
        producerSettings.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        producerSettings.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        producerSettings.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        try (var producer = new KafkaProducer<String, String>(producerSettings)) {
            var failure = assertThrows(ExecutionException.class,
                    () -> producer.send(new ProducerRecord<>(
                            allowedTopic(), "security-check", "denied-write-probe"))
                            .get(15, TimeUnit.SECONDS));
            assertInstanceOf(TopicAuthorizationException.class, failure.getCause(),
                    "Must observe broker-side authorization rejection, not a timeout");
        }
        assertHealthyBaseline(secure);
    }
}
