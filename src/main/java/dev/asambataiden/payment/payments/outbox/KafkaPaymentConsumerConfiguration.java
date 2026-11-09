package dev.asambataiden.payment.payments.outbox;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Listener registration is opt-in AND fail-closed on transport settings.
 * No automatic DLQ or poison-message skip: a processing failure stops the
 * listener; operators must investigate before restart or reviewed replay.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.messaging.kafka.consumer", name = "enabled", havingValue = "true")
public class KafkaPaymentConsumerConfiguration {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> paymentManualAckListenerFactory(
            ConsumerFactory<String, String> consumerFactory,
            @Value("${KAFKA_BOOTSTRAP_SERVERS:}") String bootstrap,
            @Value("${payment.messaging.kafka.consumer.group-id}") String groupId) {
        if (bootstrap.isBlank() || groupId == null || groupId.isBlank()) {
            throw new IllegalStateException("Kafka consumer requires bootstrap servers and stable group ID");
        }
        Map<String, Object> properties = consumerFactory.getConfigurationProperties();
        Object protocol = properties.get("security.protocol");
        if (!"SSL".equals(protocol) && !"SASL_SSL".equals(protocol)) {
            throw new IllegalStateException("Kafka consumer requires an encrypted TLS transport");
        }
        Object autoCommit = properties.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        if (Boolean.TRUE.equals(autoCommit) || "true".equalsIgnoreCase(String.valueOf(autoCommit))) {
            throw new IllegalStateException("Kafka consumer auto-offset commit must be disabled");
        }

        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setSyncCommits(true);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }

    @Bean
    OptInKafkaPaymentListener optInKafkaPaymentListener(
            JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${payment.messaging.kafka.topic}") String topic) {
        return new OptInKafkaPaymentListener(
                new LocalKafkaPaymentRecordProcessor(topic,
                        new TrustedPaymentEventConsumer(jdbc, manager)),
                new LocalPaymentEventProjection(jdbc));
    }
}
