package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerAutoCommitConfigurationTest {

    @Test
    void acceptsExplicitBooleanAndStringFalse() {
        assertDoesNotThrow(() ->
                KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(
                        Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)));
        assertDoesNotThrow(() ->
                KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(
                        Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")));
    }

    @Test
    void rejectsTrueMissingOrInvalidAutoCommitConfiguration() {
        assertAll(
                () -> assertThrows(IllegalStateException.class, () ->
                        KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(
                                Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true))),
                () -> assertThrows(IllegalStateException.class, () ->
                        KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(
                                Map.of())),
                () -> assertThrows(IllegalStateException.class, () ->
                        KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(
                                Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "invalid")))
        );
    }
}
