package dev.asambataiden.payment.payments.outbox;
import dev.asambataiden.payment.payments.messaging.kafka.producer.KafkaPaymentEventPublisher;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

/** Real broker -> strict wire validation -> PostgreSQL consumer receipt. */
@Testcontainers
@SpringBootTest
class LocalKafkaPaymentRecordProcessorIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void validatesActualKafkaRecordAndDeduplicatesReplayedDelivery() throws Exception {
        String topic = "payment.simulated.consumer.it";
        String merchant = "consumer-it-a";
        KafkaTestSupport.createTopic(kafka, topic, 2);
        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("15.00"), "EUR", "consume-it")).id();
        UUID eventId = UUID.randomUUID();
        transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED);

        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            var publisher = new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic);
            publisher.publish(new PaymentOutboxEvent(1, eventId, merchant, paymentId, "AUTHORIZED"));

            ConsumerRecord<String, String> record = null;
            try (var client = KafkaTestSupport.newConsumer(kafka, "consumer-contract")) {
                client.subscribe(List.of(topic));
                for (int i = 0; i < 8 && record == null; i++) {
                    for (var received : client.poll(Duration.ofSeconds(3))) {
                        if (received.value().contains(eventId.toString())) record = received;
                    }
                }
            }
            assertNotNull(record, "Kafka event must be received before processing");
            var processor = new LocalKafkaPaymentRecordProcessor(
                    topic, new TrustedPaymentEventConsumer(jdbc, manager));
            AtomicInteger effects = new AtomicInteger();

            assertTrue(processor.process("local-reporting", record, effects::incrementAndGet));
            assertFalse(processor.process("local-reporting", record, effects::incrementAndGet));
            assertEquals(1, effects.get());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_inbox
                    WHERE consumer_name = 'local-reporting' AND merchant_id = ? AND event_id = ?
                    """, Integer.class, merchant, eventId));

            ConsumerRecord<String, String> validRecord = record;
            assertThrows(IllegalArgumentException.class, () ->
                    processor.process("local-reporting", new ConsumerRecord<>(
                            topic, 0, 10, "another-merchant:" + paymentId,
                            validRecord.value()), effects::incrementAndGet));
            assertThrows(IllegalArgumentException.class, () ->
                    processor.process("local-reporting", new ConsumerRecord<>(
                            "wrong.topic", 0, 11, validRecord.key(), validRecord.value()),
                            effects::incrementAndGet));
            assertThrows(IllegalArgumentException.class, () ->
                    processor.process("local-reporting", new ConsumerRecord<>(
                            topic, 0, 12, validRecord.key(),
                            validRecord.value().replace("\"schemaVersion\":1", "\"schemaVersion\":2")),
                            effects::incrementAndGet));
            assertThrows(IllegalArgumentException.class, () ->
                    processor.process("local-reporting", new ConsumerRecord<>(
                            topic, 0, 13, validRecord.key(),
                            validRecord.value().replaceFirst("\\}$", ",\"unexpected\":true}")),
                            effects::incrementAndGet));
            assertEquals(1, effects.get(), "Invalid records must not execute local effects");
        } finally {
            factory.destroy();
        }
    }

    @Test
    void rejectsUntrustedRecordEvenIfItsEnvelopeLooksValid() {
        var processor = new LocalKafkaPaymentRecordProcessor(
                "payment.simulated.events.v1", new TrustedPaymentEventConsumer(jdbc, manager));
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String merchant = "forged-merchant";
        String payload = """
                {"schemaVersion":1,"eventId":"%s","merchantId":"%s","paymentId":"%s","targetState":"AUTHORIZED"}
                """.formatted(eventId, merchant, paymentId);
        var forged = new ConsumerRecord<>("payment.simulated.events.v1", 0, 0,
                merchant + ":" + paymentId, payload);
        assertThrows(SecurityException.class, () ->
                processor.process("local-reporting", forged, () -> fail("must reject forged message")));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_inbox WHERE event_id = ?", Integer.class, eventId));
    }

    @Test
    void rejectsOversizedAndMalformedPayloadsBeforeDatabaseEffects() {
        var processor = new LocalKafkaPaymentRecordProcessor(
                "payment.simulated.events.v1", new TrustedPaymentEventConsumer(jdbc, manager));
        assertThrows(IllegalArgumentException.class, () ->
                processor.process("local-reporting",
                        new ConsumerRecord<>("payment.simulated.events.v1", 0, 0, "a:b",
                                "x".repeat(4097)), () -> fail("oversize")));
        assertThrows(IllegalArgumentException.class, () ->
                processor.process("local-reporting",
                        new ConsumerRecord<>("payment.simulated.events.v1", 0, 1, "a:b", "{broken"),
                        () -> fail("malformed")));
    }
}
