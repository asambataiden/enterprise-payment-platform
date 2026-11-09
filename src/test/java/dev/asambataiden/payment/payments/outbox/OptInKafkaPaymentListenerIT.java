package dev.asambataiden.payment.payments.outbox;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Real Kafka and PostgreSQL records, explicitly invoked listener boundary.
 * This DOES NOT pretend to verify a TLS/SASL deployed listener container.
 */
@Testcontainers
@SpringBootTest
class OptInKafkaPaymentListenerIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private OptInKafkaPaymentListener listener(String topic) {
        return new OptInKafkaPaymentListener(
                new LocalKafkaPaymentRecordProcessor(topic,
                        new TrustedPaymentEventConsumer(jdbc, manager)),
                new LocalPaymentEventProjection(jdbc));
    }

    @Test
    void realKafkaRecordPersistsTypedProjectionAndAcksOnlyAfterCommit() throws Exception {
        String topic = "payment.simulated.listener.it";
        KafkaTestSupport.createTopic(kafka, topic, 1);
        String merchant = "listener-owner";
        UUID payment = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("24.00"), "EUR", "listener-it")).id();
        UUID eventId = UUID.randomUUID();
        transitions.advance(merchant, payment, eventId, PaymentState.AUTHORIZED);
        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic).publish(
                    new PaymentOutboxEvent(1, eventId, merchant, payment, "AUTHORIZED"));
            ConsumerRecord<String, String> record = null;
            try (var consumer = KafkaTestSupport.newConsumer(kafka, "listener-it")) {
                consumer.subscribe(List.of(topic));
                for (int i = 0; i < 8 && record == null; i++) {
                    for (var next : consumer.poll(Duration.ofSeconds(3))) {
                        if (next.value().contains(eventId.toString())) record = next;
                    }
                }
            }
            assertNotNull(record);
            var ack = mock(Acknowledgment.class);
            listener(topic).onMessage(record, ack);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_event_projection WHERE merchant_id=? AND event_id=? AND target_state='AUTHORIZED'",
                    Integer.class, merchant, eventId));
            verify(ack, times(1)).acknowledge();

            var replayAck = mock(Acknowledgment.class);
            listener(topic).onMessage(record, replayAck);
            verify(replayAck, times(1)).acknowledge();
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_event_projection WHERE merchant_id=? AND event_id=?",
                    Integer.class, merchant, eventId));
        } finally {
            factory.destroy();
        }
    }

    @Test
    void invalidEventCannotAckOrChangeProjection() {
        String topic = "payment.simulated.events.v1";
        var acknowledgment = mock(Acknowledgment.class);
        UUID eventId = UUID.randomUUID();
        var record = new ConsumerRecord<String, String>(topic, 0, 0, "wrong", """
                {"schemaVersion":1,"eventId":"%s","merchantId":"x","paymentId":"%s","targetState":"AUTHORIZED"}
                """.formatted(eventId, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> listener(topic).onMessage(record, acknowledgment));
        verifyNoInteractions(acknowledgment);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_event_projection WHERE event_id=?",
                Integer.class, eventId));
    }
}
