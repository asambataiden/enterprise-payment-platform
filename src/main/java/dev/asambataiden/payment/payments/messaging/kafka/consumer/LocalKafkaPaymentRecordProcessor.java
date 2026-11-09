package dev.asambataiden.payment.payments.messaging.kafka.consumer;
import dev.asambataiden.payment.payments.outbox.TrustedPaymentEventConsumer;
import dev.asambataiden.payment.payments.outbox.PaymentOutboxEvent;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicitly invoked Kafka record boundary for a same-database simulation.
 *
 * Broker authentication, topic ACLs and trusted subscriber identities are
 * prerequisites of a remote deployment; this class does NOT authenticate an
 * arbitrary Kafka producer. The matching local outbox is additional validation,
 * not an independent cross-service signature or identity proof.
 */
public final class LocalKafkaPaymentRecordProcessor {
    private static final int MAX_EVENT_BYTES = 4096;
    private static final Set<String> FIELDS =
            Set.of("schemaVersion", "eventId", "merchantId", "paymentId", "targetState");

    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final String topic;
    private final TrustedPaymentEventConsumer consumer;

    public LocalKafkaPaymentRecordProcessor(String topic, TrustedPaymentEventConsumer consumer) {
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Invalid expected Kafka topic");
        }
        this.topic = topic;
        this.consumer = Objects.requireNonNull(consumer);
    }

    /**
     * Validate all wire-level invariants before attempting a DB transaction.
     * The caller must commit the Kafka offset ONLY AFTER this method succeeds;
     * an exception leaves the offset unacknowledged for a supervised retry/DLQ.
     *
     * @return true on first local DB effect; false for a previously consumed event.
     */
    public boolean process(String consumerName, ConsumerRecord<String, String> record,
                           Runnable localDatabaseEffect) {
        Objects.requireNonNull(localDatabaseEffect);
        return processWithEvent(consumerName, record, ignored -> localDatabaseEffect.run());
    }

    /** Type-safe event effect, executed with the inbox receipt in one DB transaction. */
    public boolean processWithEvent(String consumerName, ConsumerRecord<String, String> record,
                                    Consumer<PaymentOutboxEvent> localDatabaseEffect) {
        Objects.requireNonNull(record);
        Objects.requireNonNull(localDatabaseEffect);
        if (!topic.equals(record.topic())) {
            throw new IllegalArgumentException("Unexpected Kafka topic");
        }
        String payload = record.value();
        if (payload == null || payload.isBlank() ||
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("Empty or oversized event");
        }

        Map<?, ?> fields;
        try {
            fields = json.readValue(payload, Map.class);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Kafka event JSON", invalid);
        }
        if (!fields.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("Unknown or missing event attributes");
        }
        if (!(fields.get("schemaVersion") instanceof Integer version) || version != 1) {
            throw new IllegalArgumentException("Unsupported event version");
        }

        String merchantId = string(fields, "merchantId");
        String targetState = string(fields, "targetState");
        if (merchantId.length() > 128 || !targetState.matches("AUTHORIZED|CAPTURED|FAILED|CANCELLED|REFUNDED")) {
            throw new IllegalArgumentException("Unsupported merchant or transition");
        }
        UUID eventId = uuid(fields, "eventId");
        UUID paymentId = uuid(fields, "paymentId");
        if (!Objects.equals(record.key(), merchantId + ":" + paymentId)) {
            throw new IllegalArgumentException("Kafka key does not match event ownership");
        }
        PaymentOutboxEvent event = new PaymentOutboxEvent(
                version, eventId, merchantId, paymentId, targetState);
        return consumer.consume(consumerName, event, () -> localDatabaseEffect.accept(event));
    }

    private static String string(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Invalid event field: " + key);
        }
        return text;
    }

    private static UUID uuid(Map<?, ?> fields, String key) {
        try {
            return UUID.fromString(string(fields, key));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid event UUID: " + key, invalid);
        }
    }
}
