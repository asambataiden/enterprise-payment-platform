package dev.asambataiden.payment.payments.messaging.kafka.producer;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxDispatcher;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import java.math.BigDecimal;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** Full simulated state -> Postgres outbox -> Kafka ACK -> Postgres dispatch receipt. */
@Testcontainers
@SpringBootTest
class KafkaOutboxEndToEndIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void publishesCommittedOutboxEventToRealKafkaAndAcknowledgesOnce() throws Exception {
        String merchant = "e2e-kafka-merchant";
        String topic = "payment.simulated.events.e2e";
        KafkaTestSupport.createTopic(kafka, topic, 2);

        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("22.50"), "EUR", "kafka-e2e")).id();
        UUID eventId = UUID.randomUUID();
        assertEquals(PaymentState.AUTHORIZED,
                transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED));

        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            var adapter = new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic);
            var dispatcher = new PaymentOutboxDispatcher(jdbc, transactionManager, adapter);
            assertTrue(dispatcher.dispatchOne());
            assertFalse(dispatcher.dispatchOne());
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_outbox
                    WHERE merchant_id = ? AND id = ? AND dispatched_at IS NOT NULL
                    """, Long.class, merchant, eventId));

            try (var consumer = KafkaTestSupport.newConsumer(kafka, "e2e")) {
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
