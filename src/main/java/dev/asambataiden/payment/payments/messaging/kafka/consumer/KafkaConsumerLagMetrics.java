package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * An opt-in, cached Kafka lag metric adapter. Gauge reads never contact Kafka.
 * Lag is -1 when sampling fails, has no committed offsets, or becomes stale.
 * All metrics have fixed names and no tenant, topic, partition or offset tags.
 */
public final class KafkaConsumerLagMetrics {

    private record Reading(KafkaConsumerLagAssessment.Snapshot snapshot, Instant sampledAt) {}

    private final Supplier<KafkaConsumerLagAssessment.Snapshot> sampler;
    private final Clock clock;
    private final Duration maximumAge;
    private volatile Reading latest;

    public KafkaConsumerLagMetrics(
            Supplier<KafkaConsumerLagAssessment.Snapshot> sampler,
            MeterRegistry meters,
            Clock clock,
            Duration maximumAge) {
        this.sampler = Objects.requireNonNull(sampler);
        this.clock = Objects.requireNonNull(clock);
        this.maximumAge = Objects.requireNonNull(maximumAge);
        if (maximumAge.isZero() || maximumAge.isNegative()) {
            throw new IllegalArgumentException("Lag reading maximum age must be positive");
        }
        Objects.requireNonNull(meters);
        Gauge.builder("payment.kafka.consumer.lag.records", this, KafkaConsumerLagMetrics::lag)
                .description("Committed-offset Kafka lag; -1 means unknown or stale")
                .register(meters);
        Gauge.builder("payment.kafka.consumer.lag.available", this,
                        monitor -> monitor.isAvailable() ? 1 : 0)
                .description("1 only when a complete and fresh committed-offset lag sample exists")
                .register(meters);
        Gauge.builder("payment.kafka.consumer.lag.sample.age.seconds", this,
                        KafkaConsumerLagMetrics::ageSeconds)
                .description("Age of the most recent sample; -1 before the first sample")
                .register(meters);
    }

    /** Scheduled calls are serialized, separate from metric scrapes. */
    @Scheduled(fixedDelayString = "${payment.messaging.kafka.consumer.lag-monitor.interval-ms:30000}")
    public synchronized void refresh() {
        KafkaConsumerLagAssessment.Snapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(sampler.get(), "Lag sampler returned null");
        } catch (RuntimeException failure) {
            snapshot = new KafkaConsumerLagAssessment.Snapshot(
                    KafkaConsumerLagAssessment.State.UNAVAILABLE, -1, 0);
        }
        latest = new Reading(snapshot, clock.instant());
    }

    public double lag() {
        Reading reading = freshReading();
        return reading != null && reading.snapshot().state()
                == KafkaConsumerLagAssessment.State.AVAILABLE
                ? reading.snapshot().lag() : -1;
    }

    public boolean isAvailable() {
        return lag() >= 0;
    }

    public double ageSeconds() {
        Reading reading = latest;
        if (reading == null) {
            return -1;
        }
        return Math.max(0, Duration.between(reading.sampledAt(), clock.instant()).toMillis() / 1000.0);
    }

    private Reading freshReading() {
        Reading reading = latest;
        if (reading == null) {
            return null;
        }
        Duration age = Duration.between(reading.sampledAt(), clock.instant());
        return age.isNegative() || age.compareTo(maximumAge) > 0 ? null : reading;
    }
}
