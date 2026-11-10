package dev.asambataiden.payment.payments.messaging.kafka.security;

import java.util.Map;
import java.util.Objects;

/**
 * Deployment preflight for all opt-in Kafka client identities.
 * Enforces encrypted transport and broker certificate hostname verification
 * without inspecting, logging or materializing credentials.
 *
 * This does not replace broker-side authentication/authorization tests.
 */
public final class KafkaTransportSecurity {
    private KafkaTransportSecurity() {}

    public static void validate(Map<String, ?> configuration) {
        Objects.requireNonNull(configuration, "Kafka client configuration is required");
        String protocol = String.valueOf(configuration.get("security.protocol"));
        if (!"SSL".equals(protocol) && !"SASL_SSL".equals(protocol)) {
            throw new IllegalStateException("Kafka requires SSL or SASL_SSL transport");
        }
        Object verification = configuration.get("ssl.endpoint.identification.algorithm");
        if (verification != null && !"https".equalsIgnoreCase(String.valueOf(verification))) {
            throw new IllegalStateException("Kafka TLS hostname verification must not be disabled");
        }
        if ("SASL_SSL".equals(protocol)) {
            Object mechanism = configuration.get("sasl.mechanism");
            if (mechanism == null || String.valueOf(mechanism).isBlank()) {
                throw new IllegalStateException("Kafka SASL mechanism must be explicitly configured");
            }
        }
    }
}
