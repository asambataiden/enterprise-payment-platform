package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Operator-invoked, read-only consumer group lag snapshot.
 *
 * Uses the group's durable committed offsets, NOT volatile consumer positions.
 * An unknown result must never be interpreted as zero lag or as permission to
 * skip a record. Kafka admin operations here cannot mutate broker offsets.
 */
public final class KafkaConsumerLagAssessment {
    public enum State { AVAILABLE, NO_COMMITTED_OFFSETS, UNAVAILABLE }

    public record Snapshot(State state, long lag, int partitions) {
        public Snapshot {
            Objects.requireNonNull(state);
            if (state == State.AVAILABLE && (lag < 0 || partitions < 1)) {
                throw new IllegalArgumentException("Invalid available lag snapshot");
            }
            if (state != State.AVAILABLE && (lag != -1 || partitions != 0)) {
                throw new IllegalArgumentException("Unavailable lag must be unknown");
            }
        }
    }

    private static final long TIMEOUT_SECONDS = 5;
    private final Admin admin;
    private final String groupId;
    private final String topic;

    public KafkaConsumerLagAssessment(Admin admin, String groupId, String topic) {
        this.admin = Objects.requireNonNull(admin);
        if (groupId == null || groupId.isBlank()
                || topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Valid Kafka group and topic are required");
        }
        this.groupId = groupId;
        this.topic = topic;
    }

    /**
     * Only a positive, fully observed committed partition set is measurable.
     * No committed offsets does not mean the group is caught up; a new group
     * may have a large backlog that cannot be measured by this method.
     */
    public Snapshot sample() {
        try {
            Map<TopicPartition, OffsetAndMetadata> commits = admin
                    .listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            Map<TopicPartition, OffsetSpec> endRequests = new HashMap<>();
            for (var entry : commits.entrySet()) {
                if (topic.equals(entry.getKey().topic())) {
                    if (entry.getValue() == null || entry.getValue().offset() < 0) {
                        return new Snapshot(State.UNAVAILABLE, -1, 0);
                    }
                    endRequests.put(entry.getKey(), OffsetSpec.latest());
                }
            }
            if (endRequests.isEmpty()) {
                return new Snapshot(State.NO_COMMITTED_OFFSETS, -1, 0);
            }

            // A partially initialized group must not be reported as caught up:
            // every topic partition needs an actual committed offset.
            var description = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS).get(topic);
            if (description == null || description.partitions().isEmpty()
                    || description.partitions().size() != endRequests.size()) {
                return new Snapshot(State.UNAVAILABLE, -1, 0);
            }
            for (var partition : description.partitions()) {
                if (!endRequests.containsKey(new TopicPartition(topic, partition.partition()))) {
                    return new Snapshot(State.UNAVAILABLE, -1, 0);
                }
            }

            var ends = admin.listOffsets(endRequests).all()
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long lag = 0;
            for (var entry : endRequests.entrySet()) {
                var end = ends.get(entry.getKey());
                if (end == null || end.offset() < commits.get(entry.getKey()).offset()) {
                    // Offsets can be out of sync due to retention or offset reset.
                    // Treat this as unknown; do not clamp negative lag to zero.
                    return new Snapshot(State.UNAVAILABLE, -1, 0);
                }
                lag = Math.addExact(lag,
                        end.offset() - commits.get(entry.getKey()).offset());
            }
            return new Snapshot(State.AVAILABLE, lag, endRequests.size());
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return new Snapshot(State.UNAVAILABLE, -1, 0);
        } catch (ExecutionException | TimeoutException | ArithmeticException failure) {
            return new Snapshot(State.UNAVAILABLE, -1, 0);
        }
    }
}
