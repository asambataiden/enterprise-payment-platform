package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Explicitly opt-in lag monitoring. Never enable for the default, disabled
 * consumer or silently weaken the listener's Kafka transport security.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "payment.messaging.kafka.consumer", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "payment.messaging.kafka.consumer.lag-monitor",
        name = "enabled", havingValue = "true")
public class KafkaConsumerLagMonitoringConfiguration {

    @Bean(destroyMethod = "close")
    Admin paymentConsumerLagAdmin(ConsumerFactory<String, String> consumerFactory) {
        Map<String, Object> properties = consumerFactory.getConfigurationProperties();
        Object protocol = properties.get("security.protocol");
        if (!"SSL".equals(protocol) && !"SASL_SSL".equals(protocol)) {
            throw new IllegalStateException("Lag monitoring requires encrypted Kafka transport");
        }
        KafkaPaymentConsumerConfiguration.requireExplicitManualOffsetCommit(properties);
        if (!properties.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            throw new IllegalStateException("Lag monitoring requires Kafka bootstrap servers");
        }
        // Admin supports read APIs only in our assessment; enforce broker ACLs
        // with least privilege independently when deploying this application.
        return Admin.create(properties);
    }

    @Bean
    KafkaConsumerLagMetrics paymentConsumerLagMetrics(
            Admin paymentConsumerLagAdmin,
            MeterRegistry registry,
            @Value("${payment.messaging.kafka.consumer.group-id}") String groupId,
            @Value("${payment.messaging.kafka.topic}") String topic,
            @Value("${payment.messaging.kafka.consumer.lag-monitor.interval-ms:30000}")
                    long intervalMillis,
            @Value("${payment.messaging.kafka.consumer.lag-monitor.maximum-age-ms:120000}")
                    long maximumAgeMillis) {
        if (intervalMillis < 5000 || maximumAgeMillis < intervalMillis) {
            throw new IllegalStateException(
                    "Lag monitor requires interval >= 5000 ms and maximum age >= interval");
        }
        var assessment = new KafkaConsumerLagAssessment(paymentConsumerLagAdmin, groupId, topic);
        return new KafkaConsumerLagMetrics(
                assessment::sample, registry, Clock.systemUTC(), Duration.ofMillis(maximumAgeMillis));
    }
}
