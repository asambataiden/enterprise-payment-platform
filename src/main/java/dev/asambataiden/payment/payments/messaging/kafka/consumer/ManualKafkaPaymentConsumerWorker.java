package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * An explicitly invoked, single-thread-owned Kafka consumer with fail-stop
 * semantics. Successful local DB processing MUST finish before committing
 * the associated Kafka offset; failed processing never commits that offset.
 *
 * The worker is NOT scheduled or registered as a Spring listener. A failure
 * permanently stops this instance: close it and restart a fresh consumer
 * with the same stable group ID to replay from committed offsets.
 */
public final class ManualKafkaPaymentConsumerWorker implements AutoCloseable {
    @FunctionalInterface
    public interface RecordHandler {
        void process(ConsumerRecord<String, String> record);
    }

    private final Consumer<String, String> consumer;
    private final RecordHandler handler;
    private boolean stopped;

    public ManualKafkaPaymentConsumerWorker(
            Consumer<String, String> consumer, String topic, RecordHandler handler) {
        this.consumer = Objects.requireNonNull(consumer);
        this.handler = Objects.requireNonNull(handler);
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Invalid Kafka topic");
        }
        consumer.subscribe(List.of(topic));
    }

    /**
     * Process at most one poll batch; returns records handled (including
     * inbox duplicates). Kafka auto-offset commit MUST be disabled by caller.
     * Caller must provide a local-only transactional handler.
     */
    public int pollOnce(Duration timeout) {
        if (stopped) {
            throw new IllegalStateException("Worker stopped after an error; construct a fresh consumer");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Poll timeout must be between 0 and 10 seconds");
        }
        int handled = 0;
        try {
            for (var record : consumer.poll(timeout)) {
                handler.process(record);
                consumer.commitSync(Map.of(
                        new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)));
                handled++;
            }
            return handled;
        } catch (RuntimeException failure) {
            // A poll may have delivered multiple records/partitions. We must
            // never poll again with in-memory positions advanced past them.
            // A NEW consumer replays all uncommitted offsets. Inbox handles
            // duplicates after an ambiguous commit or process crash.
            stopped = true;
            throw failure;
        }
    }

    @Override
    public void close() {
        stopped = true;
        consumer.close();
    }
}
