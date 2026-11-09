package dev.asambataiden.payment.payments.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Opt-in transport wiring only. Operators start the bounded worker explicitly;
 * the HTTP application does not poll or publish at startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "payment.messaging.kafka", name = "enabled", havingValue = "true")
public class KafkaPaymentTransportConfiguration {
    @Bean
    KafkaPaymentEventPublisher kafkaPaymentEventPublisher(
            KafkaTemplate<String, String> kafka,
            @Value("${payment.messaging.kafka.topic}") String topic) {
        return new KafkaPaymentEventPublisher(kafka, topic);
    }

    @Bean
    PaymentOutboxDispatcher kafkaOutboxDispatcher(
            JdbcTemplate jdbc, PlatformTransactionManager manager,
            KafkaPaymentEventPublisher publisher) {
        return new PaymentOutboxDispatcher(jdbc, manager, publisher);
    }

    @Bean
    SupervisedPaymentOutboxWorker kafkaOutboxWorker(PaymentOutboxDispatcher dispatcher) {
        return new SupervisedPaymentOutboxWorker(dispatcher, 25);
    }
}
