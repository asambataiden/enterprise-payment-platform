package dev.asambataiden.payment.payments.outbox;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL KafkaMessageListenerContainer startup/stop with Kafka and Postgres.
 * Test-only PLAINTEXT broker; production opt-in TLS/SASL guard is NOT bypassed.
 * The container is explicitly assembled here, not started by @KafkaListener.
 */
@Testcontainers
@SpringBootTest
class KafkaListenerContainerLifecycleIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired PaymentService payments;
    @Autowired SimulatedPaymentTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    private KafkaMessageListenerContainer<String, String> container(
            String topic, String group, SimpleMeterRegistry metrics) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);

        var props = new ContainerProperties(topic);
        props.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        props.setSyncCommits(true);
        var listener = new OptInKafkaPaymentListener(
                new LocalKafkaPaymentRecordProcessor(topic,
                        new TrustedPaymentEventConsumer(jdbc, manager)),
                new LocalPaymentEventProjection(jdbc),
                new KafkaConsumerFailureObservations(jdbc, manager),
                new KafkaConsumerMetrics(metrics));
        props.setMessageListener((AcknowledgingMessageListener<String, String>)
                (record, acknowledgment) -> listener.onMessage(record, acknowledgment));

        var container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, String>(config), props);
        container.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return container;
    }

    private static void until(BooleanSupplier condition, String explanation) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), explanation);
    }

    @Test
    void realListenerContainerConsumesCommitsAndStopsCleanly() throws Exception {
        String topic = "payment.simulated.lifecycle.valid";
        KafkaTestSupport.createTopic(kafka, topic, 1);
        var merchant = "lifecycle-owner";
        UUID paymentId = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("35.00"), "EUR", "container")).id();
        UUID eventId = UUID.randomUUID();
        transitions.advance(merchant, paymentId, eventId, PaymentState.AUTHORIZED);

        var producer = KafkaTestSupport.newProducerFactory(kafka);
        var meters = new SimpleMeterRegistry();
        var listener = container(topic, "lifecycle-ok-" + UUID.randomUUID(), meters);
        try {
            listener.start();
            assertTrue(listener.isRunning());
            new KafkaPaymentEventPublisher(new KafkaTemplate<>(producer), topic).publish(
                    new PaymentOutboxEvent(1, eventId, merchant, paymentId, "AUTHORIZED"));
            until(() -> jdbc.queryForObject("""
                    SELECT COUNT(*) FROM payment_event_projection
                    WHERE merchant_id=? AND event_id=?
                    """, Integer.class, merchant, eventId) == 1,
                    "Spring Kafka container must commit the local projection");
            until(() -> meters.get("payment.kafka.consumer.records")
                    .tag("outcome", "processed").counter().count() == 1.0,
                    "Listener must acknowledge and increment the successful metric");
        } finally {
            listener.stop();
            producer.destroy();
            meters.close();
        }
        assertFalse(listener.isRunning());
    }

    @Test
    void realListenerContainerStopsOnPoisonAndLeavesOffsetUncommitted() throws Exception {
        String topic = "payment.simulated.lifecycle.poison";
        KafkaTestSupport.createTopic(kafka, topic, 1);
        String group = "lifecycle-poison-" + UUID.randomUUID();
        var producer = KafkaTestSupport.newProducerFactory(kafka);
        var meters = new SimpleMeterRegistry();
        var listener = container(topic, group, meters);
        try {
            listener.start();
            new KafkaTemplate<String, String>(producer).send(topic, "wrong-key", "{malformed")
                    .get(10, TimeUnit.SECONDS);
            until(() -> jdbc.queryForObject("""
                    SELECT COUNT(*) FROM kafka_consumer_failure_observations
                    WHERE consumer_name='simulated-projection-v1' AND topic=?
                      AND partition_number=0 AND record_offset=0
                    """, Integer.class, topic) == 1,
                    "Poison record should leave durable failure metadata");
            until(() -> !listener.isRunning(), "Stopping error handler must stop consumer");
            assertEquals(1.0, meters.get("payment.kafka.consumer.records")
                    .tag("outcome", "invalid_contract").counter().count());
        } finally {
            listener.stop();
            producer.destroy();
            meters.close();
        }
        try (var restarted = KafkaTestSupport.newConsumerWithGroup(kafka, group)) {
            restarted.subscribe(java.util.List.of(topic));
            boolean replayed = false;
            for (int i = 0; i < 10 && !replayed; i++) {
                for (var record : restarted.poll(Duration.ofSeconds(2))) {
                    replayed |= record.offset() == 0 && record.value().equals("{malformed");
                }
            }
            assertTrue(replayed, "Unacknowledged poison record must replay for the same group");
        }
    }
}
