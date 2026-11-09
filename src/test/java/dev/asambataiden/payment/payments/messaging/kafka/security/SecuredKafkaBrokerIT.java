package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in *real secured broker* contract.
 *
 * This test is deliberately not a PLAINTEXT Testcontainer masquerading as TLS.
 * The broker, its CA and principals must be provisioned separately with genuine
 * authorization rules. No credentials or certificate material are checked in.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_SECURE_KAFKA_TEST_ENABLED", matches = "true")
class SecuredKafkaBrokerIT {

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing secure Kafka test configuration: " + name);
        }
        return value;
    }

    private static Map<String, Object> clientProperties() {
        var config = new HashMap<String, Object>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                required("PAYMENT_SECURE_KAFKA_BOOTSTRAP_SERVERS"));
        config.put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG,
                required("PAYMENT_SECURE_KAFKA_PROTOCOL"));
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 4000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 7000);
        config.put("ssl.endpoint.identification.algorithm", "https");

        String truststore = System.getenv("PAYMENT_SECURE_KAFKA_TRUSTSTORE_LOCATION");
        if (truststore != null && !truststore.isBlank()) {
            config.put("ssl.truststore.location", truststore);
            config.put("ssl.truststore.type", "PKCS12");
            config.put("ssl.truststore.password",
                    required("PAYMENT_SECURE_KAFKA_TRUSTSTORE_PASSWORD"));
        }

        if ("SASL_SSL".equals(config.get(AdminClientConfig.SECURITY_PROTOCOL_CONFIG))) {
            config.put("sasl.mechanism", required("PAYMENT_SECURE_KAFKA_SASL_MECHANISM"));
            config.put("sasl.jaas.config", required("PAYMENT_SECURE_KAFKA_SASL_JAAS"));
        }
        KafkaTransportSecurity.validate(config);
        return config;
    }

    @Test
    void authenticatedClientCanDescribeOnlyItsAuthorizedTopic() throws Exception {
        try (Admin admin = Admin.create(clientProperties())) {
            var result = admin.describeTopics(java.util.List.of(
                            required("PAYMENT_SECURE_KAFKA_ALLOWED_TOPIC")))
                    .allTopicNames().get(10, TimeUnit.SECONDS);
            assertEquals(1, result.size());
            assertFalse(result.values().iterator().next().partitions().isEmpty());
        }
    }

    @Test
    void authenticatedClientIsDeniedDescribeOnRestrictedTopic() throws Exception {
        String denied = required("PAYMENT_SECURE_KAFKA_DENIED_TOPIC");
        assertNotEquals(required("PAYMENT_SECURE_KAFKA_ALLOWED_TOPIC"), denied);
        try (Admin admin = Admin.create(clientProperties())) {
            var failure = assertThrows(ExecutionException.class,
                    () -> admin.describeTopics(java.util.List.of(denied))
                            .allTopicNames().get(10, TimeUnit.SECONDS));
            assertInstanceOf(TopicAuthorizationException.class, failure.getCause(),
                    "Broker must enforce topic ACLs; unknown-topic is not an authorization test");
        }
    }
}
