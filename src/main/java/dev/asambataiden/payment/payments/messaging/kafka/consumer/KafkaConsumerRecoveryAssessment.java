package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only forensic evidence lookup. Does not inspect or change Kafka offsets,
 * approve a restart, or grant permission to replay or skip a record.
 *
 * Invocation must be limited to an authorized operational context; this class
 * deliberately has no HTTP endpoint or consumer-group administration capability.
 */
public final class KafkaConsumerRecoveryAssessment {

    public record Observation(
            String consumerName,
            String topic,
            int partition,
            long offset,
            String payloadSha256,
            String failureCategory,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int occurrenceCount) {}

    private final JdbcTemplate jdbc;

    public KafkaConsumerRecoveryAssessment(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * Reads one exact failure location, never a broad cross-tenant listing.
     * Returning no row does not mean an offset is safe or a record was processed.
     */
    public Optional<Observation> find(
            String consumerName, String topic, int partition, long offset) {
        if (consumerName == null || consumerName.isBlank() || consumerName.length() > 128
                || topic == null || topic.isBlank() || topic.length() > 249
                || partition < 0 || offset < 0) {
            throw new IllegalArgumentException("Invalid Kafka failure location");
        }

        List<Observation> records = jdbc.query("""
                SELECT consumer_name, topic, partition_number, record_offset,
                       payload_sha256, failure_category, first_seen_at,
                       last_seen_at, occurrence_count
                FROM kafka_consumer_failure_observations
                WHERE consumer_name = ? AND topic = ?
                  AND partition_number = ? AND record_offset = ?
                """, (rs, rowNum) -> new Observation(
                rs.getString("consumer_name"),
                rs.getString("topic"),
                rs.getInt("partition_number"),
                rs.getLong("record_offset"),
                rs.getString("payload_sha256"),
                rs.getString("failure_category"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getInt("occurrence_count")),
                consumerName, topic, partition, offset);
        return records.stream().findFirst();
    }
}
