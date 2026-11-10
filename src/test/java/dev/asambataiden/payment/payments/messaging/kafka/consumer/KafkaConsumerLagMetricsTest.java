package dev.asambataiden.payment.payments.messaging.kafka.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KafkaConsumerLagMetricsTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-09T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Only UTC supported");
            return this;
        }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }

    @Test
    void gaugesNeverCallBrokerAndStaleLagBecomesUnknown() {
        var clock = new MutableClock();
        var calls = new AtomicInteger();
        var result = new AtomicReference<>(new KafkaConsumerLagAssessment.Snapshot(
                KafkaConsumerLagAssessment.State.AVAILABLE, 12, 2));
        var meters = new SimpleMeterRegistry();
        try {
            var metrics = new KafkaConsumerLagMetrics(
                    () -> { calls.incrementAndGet(); return result.get(); },
                    meters, clock, Duration.ofSeconds(90));
            var lag = meters.get("payment.kafka.consumer.lag.records").gauge();
            var available = meters.get("payment.kafka.consumer.lag.available").gauge();
            var age = meters.get("payment.kafka.consumer.lag.sample.age.seconds").gauge();

            assertEquals(-1.0, lag.value());
            assertEquals(0.0, available.value());
            assertEquals(-1.0, age.value());
            assertEquals(0, calls.get(), "Scraping must never call Kafka");

            metrics.refresh();
            assertEquals(12.0, lag.value());
            assertEquals(1.0, available.value());
            assertEquals(0.0, age.value());
            assertEquals(1, calls.get());

            clock.advance(Duration.ofSeconds(91));
            assertEquals(-1.0, lag.value(), "Stale 12 must not look like current lag");
            assertEquals(0.0, available.value());
            assertEquals(91.0, age.value());
            assertEquals(1, calls.get(), "Gauge reads stay purely local");
            assertTrue(lag.getId().getTags().isEmpty());
            assertTrue(available.getId().getTags().isEmpty());
        } finally {
            meters.close();
        }
    }

    @Test
    void failedAndUncommittedSamplesCannotReportZeroLag() {
        var clock = new MutableClock();
        var result = new AtomicReference<>(new KafkaConsumerLagAssessment.Snapshot(
                KafkaConsumerLagAssessment.State.NO_COMMITTED_OFFSETS, -1, 0));
        var meters = new SimpleMeterRegistry();
        try {
            var metrics = new KafkaConsumerLagMetrics(
                    () -> result.get(), meters, clock, Duration.ofSeconds(60));
            metrics.refresh();
            assertEquals(-1.0, metrics.lag());
            assertFalse(metrics.isAvailable());

            result.set(new KafkaConsumerLagAssessment.Snapshot(
                    KafkaConsumerLagAssessment.State.AVAILABLE, 0, 1));
            metrics.refresh();
            assertEquals(0.0, metrics.lag());
            assertTrue(metrics.isAvailable());
        } finally {
            meters.close();
        }

        var failures = new SimpleMeterRegistry();
        try {
            var metrics = new KafkaConsumerLagMetrics(
                    () -> { throw new IllegalStateException("Simulated Kafka outage"); },
                    failures, clock, Duration.ofSeconds(60));
            assertDoesNotThrow(metrics::refresh);
            assertEquals(-1.0, metrics.lag());
            assertFalse(metrics.isAvailable());
            assertEquals(0.0,
                    failures.get("payment.kafka.consumer.lag.available").gauge().value());
        } finally {
            failures.close();
        }
    }
}
