package dev.asambataiden.payment.performance;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.asambataiden.payment.payments.application.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.security.*;
import java.security.interfaces.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: local signed-JWT HTTP read/write lab, never production. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1",
    "spring.datasource.hikari.maximum-pool-size=4",
    "spring.datasource.hikari.minimum-idle=0",
    "spring.datasource.hikari.connection-timeout=1000",
    "spring.datasource.hikari.validation-timeout=500",
    "payment.simulation.enabled=false",
    "payment.messaging.kafka.enabled=false",
    "payment.messaging.kafka.consumer.enabled=false"
})
@Import(MixedHttpWorkloadLab.TrustedIssuer.class)
class MixedHttpWorkloadLab {
    private static final int REQUESTS = 300;
    private static final int CONCURRENCY = 8;
    private static final int INTERVAL_MS = 40; // at most 25 scheduled requests/second
    private static final String ISSUER = "https://mixed-load.test.invalid";
    private static final KeyPair KEY = newKey();
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");
    @TestConfiguration(proxyBeanMethods = false)
    static class TrustedIssuer {
        @Bean JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }
    @Value("${local.server.port}") int port;
    @Autowired HikariDataSource pool;
    @Autowired PaymentService payments;

    record Sample(boolean write, int status, double ms) {}
    record PoolSample(int active, int pending, int total, int dbWaiting) {}

    private static KeyPair newKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static String bearer(String merchant) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject("mixed-lab")
                .audience("enterprise-payment-service")
                .claim("merchant_id", merchant).claim("scope", "payments:read payments:write")
                .issueTime(java.util.Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return jwt.serialize();
    }
    private static double percentile(List<Double> times, double pct) {
        var sorted = new ArrayList<>(times);
        sorted.sort(Comparator.naturalOrder());
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * pct) - 1));
    }
    private Sample send(HttpClient http, URI readUrl, String token, String runId, int n)
            throws Exception {
        boolean write = n % 5 == 0; // 80 percent GET, 20 percent POST
        var target = write ? URI.create("http://127.0.0.1:" + port + "/api/v1/payments") : readUrl;
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
        if (write) {
            request.header("Idempotency-Key", "mixed-" + runId + "-" + n)
                   .header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(
                           "{\"amount\":1.25,\"currency\":\"EUR\",\"merchantReference\":\"mixed-" + n + "\"}"));
        } else request.GET();
        long begin = System.nanoTime();
        var response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        return new Sample(write, response.statusCode(), (System.nanoTime() - begin) / 1e6);
    }
    private static void report(String phase, List<Sample> results) {
        for (boolean write : new boolean[]{false, true}) {
            var values = results.stream().filter(s -> s.write() == write).toList();
            var latencies = values.stream().map(Sample::ms).toList();
            long ok = values.stream().filter(s -> s.status() == (write ? 201 : 200)).count();
            long unavailable = values.stream().filter(s -> s.status() == 503).count();
            System.out.printf(Locale.ROOT,
                    "MIXED_HTTP_LAB phase=%s op=%s samples=%d success=%d unavailable=%d unexpected=%d"
                    + " p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                    phase, write ? "write" : "read", values.size(), ok, unavailable,
                    values.size() - ok - unavailable,
                    percentile(latencies, .50), percentile(latencies, .95),
                    percentile(latencies, .99));
        }
    }

    @Test
    void boundedPacedReadWriteHttpWorkload() throws Exception {
        String merchant = "mixed-" + UUID.randomUUID();
        String runId = UUID.randomUUID().toString();
        var seed = payments.create(merchant, "seed-" + runId,
                new CreatePaymentCommand(new BigDecimal("1.25"), "EUR", "seed"));
        URI readUrl = URI.create("http://127.0.0.1:" + port + "/api/v1/payments/" + seed.id());
        String token = bearer(merchant);
        assertEquals(4, pool.getMaximumPoolSize());

        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             var workers = Executors.newFixedThreadPool(CONCURRENCY);
             var monitoring = Executors.newSingleThreadScheduledExecutor()) {
            var warmup = new ArrayList<Sample>();
            for (int n = 1; n <= 20; n++) warmup.add(send(http, readUrl, token, runId, -n));
            report("warmup", warmup);
            assertTrue(warmup.stream().allMatch(s -> s.status() == (s.write() ? 201 : 200)));

            List<PoolSample> readings = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger samplingFailures = new AtomicInteger();
            var sampler = monitoring.scheduleAtFixedRate(() -> {
                try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(),
                            postgres.getUsername(), postgres.getPassword());
                     var statement = connection.prepareStatement(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND state='active' AND wait_event_type IS NOT NULL "
                            + "AND pid <> pg_backend_pid()");
                     var rs = statement.executeQuery()) {
                    rs.next();
                    var mx = pool.getHikariPoolMXBean();
                    readings.add(new PoolSample(mx.getActiveConnections(),
                            mx.getThreadsAwaitingConnection(), mx.getTotalConnections(), rs.getInt(1)));
                } catch (Exception ex) { samplingFailures.incrementAndGet(); }
            }, 0, 250, TimeUnit.MILLISECONDS);

            long start = System.nanoTime();
            try {
                var futures = new ArrayList<Future<Sample>>();
                for (int n = 1; n <= REQUESTS; n++) {
                    final int sequence = n;
                    futures.add(workers.submit(() -> send(http, readUrl, token, runId, sequence)));
                    long deadline = start + TimeUnit.MILLISECONDS.toNanos((long) n * INTERVAL_MS);
                    long delay = deadline - System.nanoTime();
                    if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
                }
                var results = new ArrayList<Sample>();
                for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
                double elapsed = (System.nanoTime() - start) / 1e9;
                report("measured", results);
                long wrong = results.stream().filter(s -> s.status() != (s.write() ? 201 : 200)).count();
                int maxActive = readings.stream().mapToInt(PoolSample::active).max().orElse(-1);
                int maxPending = readings.stream().mapToInt(PoolSample::pending).max().orElse(-1);
                int maxDbWait = readings.stream().mapToInt(PoolSample::dbWaiting).max().orElse(-1);
                System.out.printf(Locale.ROOT, "MIXED_HTTP_LAB total=%d elapsed_sec=%.3f"
                        + " completed_rps=%.2f pool_samples=%d max_active=%d max_pending=%d"
                        + " max_db_waiting=%d monitor_failures=%d%n",
                        results.size(), elapsed, results.size() / elapsed,
                        readings.size(), maxActive, maxPending, maxDbWait, samplingFailures.get());
                assertEquals(REQUESTS, results.size());
                assertEquals(0, wrong, "Unexpected status: workload gate failed");
                assertTrue(readings.size() >= 5, "No meaningful DB/pool metrics captured");
                assertEquals(0, samplingFailures.get(), "DB monitor could not sample");
                assertTrue(maxActive <= 4 && maxActive >= 0);
            } finally { sampler.cancel(true); }
        }
    }
}
