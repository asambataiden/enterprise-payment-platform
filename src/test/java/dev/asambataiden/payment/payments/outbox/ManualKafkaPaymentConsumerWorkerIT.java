package dev.asambataiden.payment.payments.outbox;
import dev.asambataiden.payment.payments.messaging.kafka.producer.KafkaPaymentEventPublisher;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Real broker offset behavior; local effects and inbox are PostgreSQL-backed. */
@Testcontainers
@SpringBootTest
class ManualKafkaPaymentConsumerWorkerIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private PaymentOutboxEvent createEvent(String merchant) {
        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("9.50"), "EUR", "consumer-worker")).id();
        UUID eventId = UUID.randomUUID();
        transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED);
        return new PaymentOutboxEvent(1, eventId, merchant, paymentId, "AUTHORIZED");
    }

    @Test
    void commitsOnlyAfterSuccessAndDoesNotRedeliverOnRestart() throws Exception {
        String topic = "payment.simulated.commit.it";
        String group = "worker-commit-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(kafka, topic, 1);
        var event = createEvent("worker-commit-a");
        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic).publish(event);
            var processor = new LocalKafkaPaymentRecordProcessor(
                    topic, new TrustedPaymentEventConsumer(jdbc, manager));
            AtomicInteger effects = new AtomicInteger();
            int processed = 0;
            try (var worker = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> processor.process("local-worker", record, effects::incrementAndGet))) {
                for (int i = 0; i < 10 && processed == 0; i++) {
                    processed += worker.pollOnce(Duration.ofSeconds(2));
                }
            }
            assertEquals(1, processed);
            assertEquals(1, effects.get());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_inbox WHERE event_id = ? AND consumer_name = 'local-worker'",
                    Integer.class, event.eventId()));
            try (var restarted = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> fail("Committed offset must not replay"))) {
                assertEquals(0, restarted.pollOnce(Duration.ofSeconds(2)));
            }
        } finally {
            factory.destroy();
        }
    }

    @Test
    void failsClosedWithoutOffsetCommitAndReplaysAfterRestart() throws Exception {
        String topic = "payment.simulated.replay.it";
        String group = "worker-replay-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(kafka, topic, 1);
        var event = createEvent("worker-replay-a");
        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            new KafkaPaymentEventPublisher(new KafkaTemplate<>(factory), topic).publish(event);
            var processor = new LocalKafkaPaymentRecordProcessor(
                    topic, new TrustedPaymentEventConsumer(jdbc, manager));
            try (var failing = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> { throw new IllegalStateException("Simulated local processing outage"); })) {
                boolean observedFailure = false;
                for (int i = 0; i < 10 && !observedFailure; i++) {
                    try {
                        failing.pollOnce(Duration.ofSeconds(2));
                    } catch (IllegalStateException expected) {
                        assertEquals("Simulated local processing outage", expected.getMessage());
                        observedFailure = true;
                    }
                }
                assertTrue(observedFailure, "Consumer must reach the message");
                assertThrows(IllegalStateException.class, () -> failing.pollOnce(Duration.ofMillis(5)));
            }
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payment_inbox WHERE event_id = ? AND consumer_name = 'local-replay'",
                    Integer.class, event.eventId()));

            AtomicInteger effects = new AtomicInteger();
            int processed = 0;
            try (var restarted = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> processor.process("local-replay", record, effects::incrementAndGet))) {
                for (int i = 0; i < 10 && processed == 0; i++) {
                    processed += restarted.pollOnce(Duration.ofSeconds(2));
                }
            }
            assertEquals(1, processed);
            assertEquals(1, effects.get());
        } finally {
            factory.destroy();
        }
    }

    @Test
    void malformedRecordBlocksOffsetInsteadOfBeingSilentlySkipped() throws Exception {
        String topic = "payment.simulated.poison.it";
        String group = "worker-poison-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(kafka, topic, 1);
        var factory = KafkaTestSupport.newProducerFactory(kafka);
        try {
            new KafkaTemplate<String, String>(factory).send(topic, "bad-key", "{bad json")
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            var processor = new LocalKafkaPaymentRecordProcessor(
                    topic, new TrustedPaymentEventConsumer(jdbc, manager));
            boolean observedFailure = false;
            try (var failing = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> processor.process("poison-worker", record, () -> fail("must reject")))) {
                for (int i = 0; i < 10 && !observedFailure; i++) {
                    try {
                        failing.pollOnce(Duration.ofSeconds(2));
                    } catch (IllegalArgumentException expected) {
                        observedFailure = true;
                    }
                }
            }
            assertTrue(observedFailure);
            AtomicInteger replayed = new AtomicInteger();
            try (var restarted = new ManualKafkaPaymentConsumerWorker(
                    KafkaTestSupport.newConsumerWithGroup(kafka, group), topic,
                    record -> { replayed.incrementAndGet(); throw new IllegalArgumentException("quarantine needed"); })) {
                for (int i = 0; i < 10 && replayed.get() == 0; i++) {
                    try {
                        restarted.pollOnce(Duration.ofSeconds(2));
                    } catch (IllegalArgumentException expected) {
                        assertEquals("quarantine needed", expected.getMessage());
                    }
                }
            }
            assertEquals(1, replayed.get(), "The same poison offset must replay until operator intervention");
        } finally {
            factory.destroy();
        }
    }
}
