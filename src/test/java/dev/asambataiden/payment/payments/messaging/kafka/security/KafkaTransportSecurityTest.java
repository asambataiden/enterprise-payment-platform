package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaTransportSecurityTest {
    @Test
    void acceptsEncryptedTransportWithHostnameVerification() {
        assertDoesNotThrow(() -> KafkaTransportSecurity.validate(
                Map.of("security.protocol", "SSL",
                       "ssl.endpoint.identification.algorithm", "https")));
        assertDoesNotThrow(() -> KafkaTransportSecurity.validate(
                Map.of("security.protocol", "SASL_SSL",
                       "sasl.mechanism", "SCRAM-SHA-512",
                       "ssl.endpoint.identification.algorithm", "https")));
        // Kafka's default hostname check is https; absence does not disable it.
        assertDoesNotThrow(() -> KafkaTransportSecurity.validate(
                Map.of("security.protocol", "SSL")));
    }

    @Test
    void rejectsUnencryptedUnknownAndDisabledHostnameChecks() {
        for (String protocol : new String[] {"PLAINTEXT", "SASL_PLAINTEXT", "unknown"}) {
            assertThrows(IllegalStateException.class, () ->
                    KafkaTransportSecurity.validate(Map.of("security.protocol", protocol)));
        }
        var disabled = new HashMap<String, Object>();
        disabled.put("security.protocol", "SSL");
        disabled.put("ssl.endpoint.identification.algorithm", "");
        assertThrows(IllegalStateException.class, () -> KafkaTransportSecurity.validate(disabled));
        disabled.put("ssl.endpoint.identification.algorithm", "none");
        assertThrows(IllegalStateException.class, () -> KafkaTransportSecurity.validate(disabled));
        assertThrows(IllegalStateException.class, () -> KafkaTransportSecurity.validate(Map.of()));
    }

    @Test
    void requiresExplicitSaslMechanismWithoutReadingOrLoggingSecrets() {
        assertThrows(IllegalStateException.class, () -> KafkaTransportSecurity.validate(
                Map.of("security.protocol", "SASL_SSL")));
        assertThrows(IllegalStateException.class, () -> KafkaTransportSecurity.validate(
                Map.of("security.protocol", "SASL_SSL", "sasl.mechanism", " ")));
        var config = Map.<String, Object>of(
                "security.protocol", "SASL_SSL",
                "sasl.mechanism", "SCRAM-SHA-512",
                "sasl.jaas.config", "this-secret-must-not-appear-in-errors");
        assertDoesNotThrow(() -> KafkaTransportSecurity.validate(config));
    }
}
