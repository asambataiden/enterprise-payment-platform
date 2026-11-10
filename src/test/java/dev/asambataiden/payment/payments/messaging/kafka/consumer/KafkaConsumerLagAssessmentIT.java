package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import dev.asambataiden.payment.testinfra.KafkaTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaConsumerLagAssessmentIT {
    @Container
    static final KafkaContainer kafka = KafkaTestSupport.newBroker();

    @Test
    void measuresCommittedLagAcrossAllTopicPartitionsWithoutChangingOffsets()
            throws Exception {
        String topic = "payment.simulated.lag.it";
        String group = "lag-review-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(kafka, topic, 2);
        var producer = KafkaTestSupport.newProducerFactory(kafka);
        var p0 = new TopicPartition(topic, 0);
        var p1 = new TopicPartition(topic, 1);

        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            var assessor = new KafkaConsumerLagAssessment(admin, group, topic);

            // Group with no committed positions has UNKNOWN lag, not zero.
            assertEquals(KafkaConsumerLagAssessment.State.NO_COMMITTED_OFFSETS,
                    assessor.sample().state());

            try {
                var template = new KafkaTemplate<String, String>(producer);
                template.send(topic, 0, "a", "one").get(10, TimeUnit.SECONDS);
                template.send(topic, 0, "a", "two").get(10, TimeUnit.SECONDS);
                template.send(topic, 1, "b", "three").get(10, TimeUnit.SECONDS);

                try (var consumer = KafkaTestSupport.newConsumerWithGroup(kafka, group)) {
                    consumer.assign(List.of(p0, p1));
                    consumer.poll(Duration.ofSeconds(2));
                    consumer.commitSync(Map.of(
                            p0, new OffsetAndMetadata(1),
                            p1, new OffsetAndMetadata(0)));
                }

                var snapshot = assessor.sample();
                assertEquals(KafkaConsumerLagAssessment.State.AVAILABLE, snapshot.state());
                assertEquals(2, snapshot.lag());
                assertEquals(2, snapshot.partitions());

                // This diagnostic did not mutate the committed offsets.
                var committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
                assertEquals(1, committed.get(p0).offset());
                assertEquals(0, committed.get(p1).offset());
            } finally {
                producer.destroy();
            }
        }
    }

    @Test
    void refusesToReportPartialPartitionCoverageAsHealthy() throws Exception {
        String topic = "payment.simulated.partial.lag.it";
        String group = "lag-partial-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(kafka, topic, 2);
        var p0 = new TopicPartition(topic, 0);
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
             var consumer = KafkaTestSupport.newConsumerWithGroup(kafka, group)) {
            consumer.assign(List.of(p0));
            consumer.poll(Duration.ofSeconds(2));
            consumer.commitSync(Map.of(p0, new OffsetAndMetadata(0)));

            var snapshot = new KafkaConsumerLagAssessment(admin, group, topic).sample();
            assertEquals(KafkaConsumerLagAssessment.State.UNAVAILABLE, snapshot.state());
            assertEquals(-1, snapshot.lag());
        }
    }
}
