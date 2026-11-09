package dev.asambataiden.payment.payments.outbox;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Full simulated state -> Postgres outbox -> Kafka ACK -> Postgres dispatch receipt. */
@Testcontainers
@SpringBootTest
class KafkaOutboxEndToEndIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka-native:4.1.0");

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void publishesCommittedOutboxEventToRealKafkaAndAcknowledgesOnce() throws Exception {
        String merchant = "e2e-kafka-merchant";
        String topic = "payment.simulated.events.e2e";
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 2, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
        }

        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("22.50"), "EUR", "kafka-e2e")).id();
        UUID eventId = UUID.randomUUID();
        assertEquals(PaymentState.AUTHORIZED,
                transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED));

        Map<String, Object> producerConfig = new HashMap<>();
        producerConfig.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        var factory = new DefaultKafkaProducerFactory<String, String>(producerConfig);
        try {
            var adapter = new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic);
            var dispatcher = new PaymentOutboxDispatcher(jdbc, transactionManager, adapter);
            assertTrue(dispatcher.dispatchOne());
            assertFalse(dispatcher.dispatchOne());
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_outbox
                    WHERE merchant_id = ? AND id = ? AND dispatched_at IS NOT NULL
                    """, Long.class, merchant, eventId));

            Map<String, Object> consumerConfig = new HashMap<>();
            consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "e2e-" + UUID.randomUUID());
            consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            try (var consumer = new KafkaConsumer<String, String>(consumerConfig)) {
                consumer.subscribe(List.of(topic));
                org.apache.kafka.clients.consumer.ConsumerRecord<String, String> found = null;
                for (int attempt = 0; attempt < 8 && found == null; attempt++) {
                    for (var record : consumer.poll(Duration.ofSeconds(3))) {
                        if (record.value().contains(eventId.toString())) found = record;
                    }
                }
                assertNotNull(found, "Committed outbox message must be consumed from Kafka");
                assertEquals(merchant + ":" + paymentId, found.key());
            }
        } finally {
            factory.destroy();
        }
    }
}
