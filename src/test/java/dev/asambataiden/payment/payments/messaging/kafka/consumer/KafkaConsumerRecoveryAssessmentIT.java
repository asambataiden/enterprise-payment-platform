package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class KafkaConsumerRecoveryAssessmentIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;

    @Test
    void exactFailureLocationReturnsMetadataWithoutRawEventOrOffsetMutation() {
        String topic = "payment.simulated.recovery." + UUID.randomUUID();
        String secretMarker = "NEVER_PERSIST_" + UUID.randomUUID();
        var record = new ConsumerRecord<String, String>(
                topic, 2, 91L, "secret-key", "{invalid:" + secretMarker);

        var recorder = new KafkaConsumerFailureObservations(jdbc, manager);
        recorder.record("recovery-review-v1", record, "INVALID_CONTRACT");
        recorder.record("recovery-review-v1", record, "INVALID_CONTRACT");

        var assessment = new KafkaConsumerRecoveryAssessment(jdbc);
        var observation = assessment.find("recovery-review-v1", topic, 2, 91L)
                .orElseThrow();

        assertAll(
                () -> assertEquals("INVALID_CONTRACT", observation.failureCategory()),
                () -> assertEquals(2, observation.occurrenceCount()),
                () -> assertEquals(64, observation.payloadSha256().length()),
                () -> assertNotNull(observation.firstSeenAt()),
                () -> assertNotNull(observation.lastSeenAt()),
                () -> assertFalse(observation.toString().contains(secretMarker)),
                () -> assertFalse(observation.toString().contains("secret-key")),
                () -> assertTrue(assessment.find("recovery-review-v1", topic, 2, 92L)
                        .isEmpty()),
                () -> assertTrue(assessment.find("another-consumer", topic, 2, 91L)
                        .isEmpty()));
        assertEquals(2, jdbc.queryForObject("""
                SELECT occurrence_count FROM kafka_consumer_failure_observations
                WHERE consumer_name=? AND topic=? AND partition_number=? AND record_offset=?
                """, Integer.class, "recovery-review-v1", topic, 2, 91L));
    }

    @Test
    void rejectsInvalidRecoveryLookups() {
        var assessment = new KafkaConsumerRecoveryAssessment(jdbc);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> assessment.find(" ", "topic", 0, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> assessment.find("consumer", " ", 0, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> assessment.find("consumer", "topic", -1, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> assessment.find("consumer", "topic", 0, -1)));
    }
}
