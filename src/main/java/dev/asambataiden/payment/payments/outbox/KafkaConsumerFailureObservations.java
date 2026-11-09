package dev.asambataiden.payment.payments.outbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records safe forensic metadata in a new transaction. This is NOT a Kafka DLQ
 * and NEVER authorizes offset advancement or event replay.
 */
public final class KafkaConsumerFailureObservations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public KafkaConsumerFailureObservations(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String consumerName, ConsumerRecord<String, String> record, String category) {
        Objects.requireNonNull(record);
        if (consumerName == null || consumerName.isBlank() || consumerName.length() > 128) {
            throw new IllegalArgumentException("Invalid consumer name");
        }
        if (!"INVALID_CONTRACT".equals(category) && !"UNTRUSTED_EVENT".equals(category)
                && !"PROCESSING_FAILURE".equals(category)) {
            throw new IllegalArgumentException("Unsupported failure category");
        }
        if (record.topic() == null || record.topic().isBlank() || record.topic().length() > 249
                || record.partition() < 0 || record.offset() < 0) {
            throw new IllegalArgumentException("Invalid Kafka record location");
        }
        String payload = record.value() == null ? "" : record.value();
        String digest;
        try {
            digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 provider missing", impossible);
        }
        final String hash = digest;
        transactions.executeWithoutResult(status -> jdbc.update("""
            INSERT INTO kafka_consumer_failure_observations
                (consumer_name, topic, partition_number, record_offset, payload_sha256, failure_category)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (consumer_name, topic, partition_number, record_offset)
            DO UPDATE SET last_seen_at=CURRENT_TIMESTAMP,
                          occurrence_count=kafka_consumer_failure_observations.occurrence_count + 1
            """, consumerName, record.topic(), record.partition(), record.offset(), hash, category));
    }
}
