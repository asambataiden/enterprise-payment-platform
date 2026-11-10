package dev.asambataiden.payment.performance;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.asambataiden.payment.payments.application.*;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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
@Import({MixedHttpWorkloadLab.TrustedIssuer.class, MixedHttpWorkloadLab.RequestTimingConfig.class})
class MixedHttpWorkloadLab {
    private static final int RATE = Integer.getInteger("mixed.lab.rate", 25);
    // Legacy tests remain 12 seconds by default; sustained stages are explicit.
    private static final int DURATION_SECONDS =
            Integer.getInteger("mixed.lab.duration.seconds", 12);
    private static final int REQUESTS = RATE * DURATION_SECONDS;
    private static final int RECORDS_PER_MERCHANT =
            Integer.getInteger("mixed.lab.seed.records", 8);
    private static final int CONCURRENCY = 8;
    private static final int MAX_IN_FLIGHT = 32;
    static {
        if (RATE != 25 && RATE != 50 && RATE != 100) {
            throw new IllegalArgumentException("mixed.lab.rate must be 25, 50, or 100");
        }
        if (DURATION_SECONDS != 12 && DURATION_SECONDS != 30 && DURATION_SECONDS != 60) {
            throw new IllegalArgumentException(
                    "mixed.lab.duration.seconds must be 12, 30, or 60");
        }
        if (RECORDS_PER_MERCHANT != 8 && RECORDS_PER_MERCHANT != 128) {
            throw new IllegalArgumentException("mixed.lab.seed.records must be 8 or 128");
        }
        if (REQUESTS > 6000) {
            throw new IllegalArgumentException("Safety cap: at most 6000 measured requests");
        }
    }
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
    // Test-only in-JVM measurements. Never expose merchant identifiers or
    // request bodies, and never register this filter in application runtime.
    private static final ConcurrentMap<Integer, ServerTiming> SERVER_TIMINGS =
            new ConcurrentHashMap<>();
    private static volatile long measuredServerOriginNs;

    record ServerTiming(double startOffsetMs, double elapsedMs) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RequestTimingConfig {
        @Bean
        FilterRegistrationBean<Filter> syntheticRequestTimingFilter() {
            Filter filter = (request, response, chain) -> {
                var httpRequest = (HttpServletRequest) request;
                String marker = httpRequest.getHeader("X-Lab-Sequence");
                if (marker == null) {
                    chain.doFilter(request, response);
                    return;
                }
                int sequence;
                try {
                    sequence = Integer.parseInt(marker);
                } catch (NumberFormatException ex) {
                    throw new jakarta.servlet.ServletException("Invalid synthetic sequence");
                }
                if (sequence < 1 || sequence > 6000 || measuredServerOriginNs == 0) {
                    throw new jakarta.servlet.ServletException("Out-of-range synthetic sequence");
                }
                long startNs = System.nanoTime();
                try {
                    chain.doFilter(request, response);
                } finally {
                    long endNs = System.nanoTime();
                    ServerTiming previous = SERVER_TIMINGS.putIfAbsent(sequence,
                            new ServerTiming((startNs - measuredServerOriginNs) / 1e6,
                                    (endNs - startNs) / 1e6));
                    if (previous != null) {
                        throw new jakarta.servlet.ServletException("Duplicate synthetic sequence");
                    }
                }
            };
            var registration = new FilterRegistrationBean<Filter>();
            registration.setFilter(filter);
            registration.addUrlPatterns("/api/v1/payments", "/api/v1/payments/*");
            // Register before Spring Security so this test-only server
            // duration includes authentication and controller processing.
            registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @Value("${local.server.port}") int port;
    @Autowired HikariDataSource pool;
    @Autowired PaymentService payments;

    record Sample(boolean write, int status, double ms, int merchantIndex, int window, int sequence,
                  double clientStartOffsetMs, double clientEndOffsetMs) {}
    record PoolSample(int active, int idle, int pending, int total, int dbWaiting) {}
    record MerchantTarget(String merchant, String token, List<URI> readUrls) {}

    private static final int MERCHANTS = 4;
    private static final long CPU_UNAVAILABLE = -1L;

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
    private Sample send(HttpClient http, List<MerchantTarget> merchants, String runId, int n,
                        long measuredOriginNs)
            throws Exception {
        boolean write = n % 5 == 0; // 80 percent GET, 20 percent POST
        int requestNumber = Math.abs(n);
        // Crucially, tenant choice is not correlated with write frequency.
        int merchantIndex = MixedHttpTenantDistribution.merchantIndex(n);
        var merchant = merchants.get(merchantIndex);
        var target = write ? URI.create("http://127.0.0.1:" + port + "/api/v1/payments")
                : merchant.readUrls().get((requestNumber / 10) % RECORDS_PER_MERCHANT);
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + merchant.token());
        if (n > 0) request.header("X-Lab-Sequence", Integer.toString(n));
        if (write) {
            request.header("Idempotency-Key", "mixed-" + runId + "-" + n)
                   .header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(
                           "{\"amount\":1.25,\"currency\":\"EUR\",\"merchantReference\":\"mixed-" + n + "\"}"));
        } else request.GET();
        long begin = System.nanoTime();
        var response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        long finish = System.nanoTime();
        // Window is based on the intended dispatch sequence, not the
        // completion time, so a slow response cannot move to another bucket.
        int window = n < 0 ? -1 : (n - 1) / (RATE * 10);
        return new Sample(write, response.statusCode(),
                (finish - begin) / 1e6, merchantIndex, window, n,
                measuredOriginNs == 0 ? -1 : (begin - measuredOriginNs) / 1e6,
                measuredOriginNs == 0 ? -1 : (finish - measuredOriginNs) / 1e6);
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

    /**
     * Explainable latency breakdowns for sustained 30/60-second stages.
     * Aggregate read and write separately; tenant percentiles are not
     * meaningful if one operation receives too few samples.
     */
    private static void reportBreakdowns(List<Sample> samples, double elapsedSeconds) {
        if (DURATION_SECONDS < 30) return;
        int windows = (DURATION_SECONDS + 9) / 10;
        int sumWindowSamples = 0;
        for (int window = 0; window < windows; window++) {
            final int targetWindow = window;
            for (boolean write : new boolean[]{false, true}) {
                var bucket = samples.stream().filter(s -> s.window() == targetWindow
                        && s.write() == write).toList();
                assertFalse(bucket.isEmpty(), "Missing time-window request samples");
                long success = bucket.stream()
                        .filter(s -> s.status() == (write ? 201 : 200)).count();
                System.out.printf(Locale.ROOT,
                        "MIXED_HTTP_LAB breakdown=window start_sec=%d end_sec=%d"
                        + " op=%s samples=%d success=%d p50_ms=%.3f p95_ms=%.3f"
                        + " p99_ms=%.3f%n",
                        window * 10, Math.min((window + 1) * 10, DURATION_SECONDS),
                        write ? "write" : "read", bucket.size(), success,
                        percentile(bucket.stream().map(Sample::ms).toList(), .50),
                        percentile(bucket.stream().map(Sample::ms).toList(), .95),
                        percentile(bucket.stream().map(Sample::ms).toList(), .99));
                sumWindowSamples += bucket.size();
            }
        }
        assertEquals(samples.size(), sumWindowSamples, "Time windows must cover all requests");

        int sumMerchantSamples = 0;
        for (int tenant = 0; tenant < MERCHANTS; tenant++) {
            final int merchant = tenant;
            for (boolean write : new boolean[]{false, true}) {
                var bucket = samples.stream().filter(s -> s.merchantIndex() == merchant
                        && s.write() == write).toList();
                assertFalse(bucket.isEmpty(), "Missing merchant/operation samples");
                long success = bucket.stream()
                        .filter(s -> s.status() == (write ? 201 : 200)).count();
                System.out.printf(Locale.ROOT,
                        "MIXED_HTTP_LAB breakdown=tenant merchant_index=%d"
                        + " op=%s samples=%d success=%d completed_rps=%.3f"
                        + " p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                        merchant, write ? "write" : "read", bucket.size(), success,
                        success / elapsedSeconds,
                        percentile(bucket.stream().map(Sample::ms).toList(), .50),
                        percentile(bucket.stream().map(Sample::ms).toList(), .95),
                        percentile(bucket.stream().map(Sample::ms).toList(), .99));
                sumMerchantSamples += bucket.size();
            }
        }
        assertEquals(samples.size(), sumMerchantSamples, "Every sample must have one tenant");
    }

    /**
     * Optional bounded, synthetic-only evidence export. Never write bearer
     * tokens, merchant identifiers, payment IDs or request bodies.
     * The script supplies an output path in the test target directory.
     */
    private static void exportRawSamples(List<Sample> samples) throws Exception {
        String configured = System.getProperty("mixed.lab.csv.path");
        if (configured == null || configured.isBlank()) return;
        Path expectedDirectory = Path.of("target", "http-load-lab").toAbsolutePath().normalize();
        Path output = Path.of(configured).toAbsolutePath().normalize();
        if (!output.startsWith(expectedDirectory) || !output.toString().endsWith(".csv")) {
            throw new IllegalArgumentException("Raw CSV must be under target/http-load-lab and end with .csv");
        }
        Files.createDirectories(expectedDirectory);
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("sequence,merchant_index,operation,http_status,latency_ms,window_start_second"
                    + ",client_start_offset_ms,client_end_offset_ms"
                    + ",server_start_offset_ms,server_elapsed_ms");
            writer.newLine();
            for (Sample s : samples) {
                ServerTiming server = SERVER_TIMINGS.get(s.sequence());
                if (server == null) {
                    throw new IllegalStateException("Missing server-side timing for synthetic request "
                            + s.sequence());
                }
                writer.write(String.format(Locale.ROOT, "%d,%d,%s,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f",
                        s.sequence(), s.merchantIndex(), s.write() ? "POST" : "GET",
                        s.status(), s.ms(), s.window() * 10,
                        s.clientStartOffsetMs(), s.clientEndOffsetMs(),
                        server.startOffsetMs(), server.elapsedMs()));
                writer.newLine();
            }
        }
        System.out.printf("MIXED_HTTP_LAB csv_rows=%d raw_csv=%s%n", samples.size(), output);
    }

    @Test
    void boundedPacedReadWriteHttpWorkload() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Boolean.getBoolean("mixed.lab.independent.client"),
                "Independent process experiment runs the separate opt-in test");
        String runId = UUID.randomUUID().toString();
        List<MerchantTarget> merchants = new ArrayList<>();
        for (int m = 0; m < MERCHANTS; m++) {
            String merchant = "mixed-" + runId + "-" + m;
            List<URI> urls = new ArrayList<>();
            for (int index = 0; index < RECORDS_PER_MERCHANT; index++) {
                var seed = payments.create(merchant, "seed-" + runId + "-" + index,
                        new CreatePaymentCommand(new BigDecimal("1.25"),
                                "EUR", "seed-" + index));
                urls.add(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/payments/" + seed.id()));
            }
            merchants.add(new MerchantTarget(merchant, bearer(merchant), List.copyOf(urls)));
        }
        assertEquals(MERCHANTS, merchants.size());
        assertEquals(4, pool.getMaximumPoolSize());

        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             var workers = Executors.newFixedThreadPool(CONCURRENCY);
             var monitoring = Executors.newSingleThreadScheduledExecutor()) {
            var warmup = new ArrayList<Sample>();
            for (int n = 1; n <= 20; n++) warmup.add(send(http, merchants, runId, -n, 0));
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
                            mx.getIdleConnections(), mx.getThreadsAwaitingConnection(),
                            mx.getTotalConnections(), rs.getInt(1)));
                } catch (Exception ex) { samplingFailures.incrementAndGet(); }
            }, 0, 250, TimeUnit.MILLISECONDS);

            var memory = java.lang.management.ManagementFactory.getMemoryMXBean();
            var threading = java.lang.management.ManagementFactory.getThreadMXBean();
            var collectors = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            var extendedOs = (os instanceof com.sun.management.OperatingSystemMXBean bean)
                    ? bean : null;
            long cpuStart = extendedOs == null ? CPU_UNAVAILABLE
                    : extendedOs.getProcessCpuTime();
            long gcCountStart = collectors.stream().mapToLong(bean ->
                    Math.max(0, bean.getCollectionCount())).sum();
            SERVER_TIMINGS.clear();
            long start = System.nanoTime();
            measuredServerOriginNs = start;
            // JVM unified logs use JVM uptime; capture uptime at the
            // same measurement boundary for approximate GC time alignment.
            long jvmUptimeAtStageStartMs =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            System.out.printf(Locale.ROOT,
                    "MIXED_HTTP_LAB test_jvm_pid=%d%n", ProcessHandle.current().pid());
            System.out.printf(Locale.ROOT,
                    "MIXED_HTTP_LAB stage_start_jvm_uptime_ms=%d"
                    + " timing_basis=monotonic_client_offsets%n",
                    jvmUptimeAtStageStartMs);
            var permits = new java.util.concurrent.Semaphore(MAX_IN_FLIGHT);
            var maxBacklog = new java.util.concurrent.atomic.AtomicInteger();
            long maxScheduleLagNs = 0;
            try {
                var futures = new ArrayList<Future<Sample>>();
                for (int n = 1; n <= REQUESTS; n++) {
                    long due = start + TimeUnit.SECONDS.toNanos(1) * (n - 1) / RATE;
                    long delay = due - System.nanoTime();
                    if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
                    maxScheduleLagNs = Math.max(maxScheduleLagNs, System.nanoTime() - due);
                    // Fail instead of growing an unbounded work queue when the generator
                    // cannot keep up with the offered rate.
                    assertTrue(permits.tryAcquire(), "Load generator backpressure: >32 outstanding calls");
                    int outstanding = MAX_IN_FLIGHT - permits.availablePermits();
                    maxBacklog.accumulateAndGet(outstanding, Math::max);
                    final int sequence = n;
                    try {
                        futures.add(workers.submit(() -> {
                            try {
                                return send(http, merchants, runId, sequence, start);
                            } finally {
                                permits.release();
                            }
                        }));
                    } catch (RuntimeException rejected) {
                        permits.release();
                        throw rejected;
                    }
                }
                var results = new ArrayList<Sample>();
                for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
                double elapsed = (System.nanoTime() - start) / 1e9;
                assertEquals(REQUESTS, SERVER_TIMINGS.size(),
                        "Exactly one server-side timing per measured request");
                for (Sample sample : results) {
                    ServerTiming server = SERVER_TIMINGS.get(sample.sequence());
                    assertNotNull(server, "Missing server-side timing");
                    // Client timing includes HTTP transport and response handling.
                    // Server filter measures only server request processing.
                    assertTrue(Double.isFinite(server.elapsedMs()) && server.elapsedMs() >= 0);
                    assertTrue(Double.isFinite(server.startOffsetMs()));
                }
                report("measured", results);
                reportBreakdowns(results, elapsed);
                exportRawSamples(results);
                long wrong = results.stream().filter(s -> s.status() != (s.write() ? 201 : 200)).count();
                // Take a stable snapshot while the periodic sampler may still run.
                List<PoolSample> captured;
                synchronized (readings) {
                    captured = List.copyOf(readings);
                }
                int maxActive = captured.stream().mapToInt(PoolSample::active).max().orElse(-1);
                double avgActive = captured.stream().mapToInt(PoolSample::active).average().orElse(-1);
                double avgIdle = captured.stream().mapToInt(PoolSample::idle).average().orElse(-1);
                long pendingSamples = captured.stream().filter(s -> s.pending() > 0).count();
                int maxPending = captured.stream().mapToInt(PoolSample::pending).max().orElse(-1);
                int maxDbWait = captured.stream().mapToInt(PoolSample::dbWaiting).max().orElse(-1);
                long gcCountEnd = collectors.stream().mapToLong(bean ->
                        Math.max(0, bean.getCollectionCount())).sum();
                long cpuEnd = extendedOs == null ? CPU_UNAVAILABLE
                        : extendedOs.getProcessCpuTime();
                double cpuSeconds = cpuEnd < 0 || cpuStart < 0 ? Double.NaN
                        : (cpuEnd - cpuStart) / 1e9;
                System.out.printf(Locale.ROOT,
                        "MIXED_HTTP_LAB telemetry merchants=%d records_per_merchant=%d"
                        + " avg_active=%.3f avg_idle=%.3f pending_samples=%d"
                        + " heap_used_mib=%.2f threads=%d gc_collections=%d"
                        + " process_cpu_sec=%.3f%n",
                        MERCHANTS, RECORDS_PER_MERCHANT,
                        avgActive, avgIdle, pendingSamples,
                        memory.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0),
                        threading.getThreadCount(), gcCountEnd - gcCountStart, cpuSeconds);
                System.out.printf(Locale.ROOT, "MIXED_HTTP_LAB rate=%d duration_target_sec=%d total=%d elapsed_sec=%.3f"
                        + " completed_rps=%.2f pool_samples=%d max_active=%d max_pending=%d"
                        + " max_db_waiting=%d monitor_failures=%d max_outstanding=%d max_schedule_lag_ms=%.3f%n",
                        RATE, DURATION_SECONDS, results.size(), elapsed, results.size() / elapsed,
                        captured.size(), maxActive, maxPending, maxDbWait, samplingFailures.get(),
                        maxBacklog.get(), maxScheduleLagNs / 1e6);
                assertEquals(REQUESTS, results.size());
                assertEquals(0, wrong, "Unexpected status: workload gate failed");
                // At 250-ms sampling, require most expected samples. Do not
                // silently accept missing telemetry on a longer stage.
                assertTrue(captured.size() >= DURATION_SECONDS * 3,
                        "Pool/DB sampling coverage too low for this duration");
                assertEquals(0, samplingFailures.get(), "DB monitor could not sample");
                assertTrue(maxActive <= 4 && maxActive >= 0);
            } finally { sampler.cancel(true); }
        }
    }
    /**
     * Milestone 24 — separate Python client process and server JVM.
     * This is opt-in only; no production payment paths or service settings
     * are changed. JVM and Python monotonic timestamps must NEVER be
     * directly subtracted. Pair durations by the synthetic sequence ID.
     */
    @Test
    void boundedIndependentProcessClientWorkload() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("mixed.lab.independent.client"),
                "Explicit opt-in only");
        assertEquals(100, RATE, "Independent process requires 100 RPS");
        assertEquals(60, DURATION_SECONDS, "Independent process requires 60 seconds");
        assertEquals(128, RECORDS_PER_MERCHANT, "Independent process requires 128 records/merchant");
        assertEquals(6000, REQUESTS);
        assertEquals(4, pool.getMaximumPoolSize());

        String configured = System.getProperty("mixed.lab.independent.output");
        assertNotNull(configured, "Independent evidence output prefix required");
        Path directory = Path.of("target", "http-load-lab").toAbsolutePath().normalize();
        Path prefix = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(prefix.startsWith(directory), "Output prefix must be under target/http-load-lab");
        Files.createDirectories(directory);
        Path childCsv = Path.of(prefix + "-independent-client.csv");
        Path serverCsv = Path.of(prefix + "-independent-server.csv");
        Path childLog = Path.of(prefix + "-independent-client.log");
        Path script = Path.of("ops", "benchmarks", "http", "independent-client-local.py")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(script), "Missing Python localhost workload script");
        String runId = UUID.randomUUID().toString();
        List<MerchantTarget> merchants = new ArrayList<>();
        for (int m = 0; m < MERCHANTS; m++) {
            String merchant = "mixed-" + runId + "-" + m;
            List<URI> urls = new ArrayList<>();
            for (int index = 0; index < RECORDS_PER_MERCHANT; index++) {
                var seed = payments.create(merchant, "seed-" + runId + "-" + index,
                        new CreatePaymentCommand(new BigDecimal("1.25"),
                                "EUR", "seed-" + index));
                urls.add(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/payments/" + seed.id()));
            }
            merchants.add(new MerchantTarget(merchant, bearer(merchant), List.copyOf(urls)));
        }

        // Start timing before the first independent HTTP request.
        SERVER_TIMINGS.clear();
        measuredServerOriginNs = System.nanoTime();
        System.out.printf(Locale.ROOT, "INDEPENDENT_LAB server_jvm_pid=%d"
                + " server_stage_start_jvm_uptime_ms=%d%n",
                ProcessHandle.current().pid(),
                java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        var processBuilder = new ProcessBuilder(
                "python3", "-u", script.toString(), childCsv.toString());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(childLog.toFile());
        // No token appears in process arguments, file names or environment.
        Process child = processBuilder.start();
        try {
            try (var writer = new BufferedWriter(
                    new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(Integer.toString(port));
                writer.newLine();
                writer.write(runId);
                writer.newLine();
                writer.write(Integer.toString(RECORDS_PER_MERCHANT));
                writer.newLine();
                for (MerchantTarget target : merchants) {
                    writer.write(target.token());
                    writer.newLine();
                }
                for (MerchantTarget target : merchants) {
                    writer.write(String.join("\t", target.readUrls().stream()
                            .map(URI::toString).toList()));
                    writer.newLine();
                }
                writer.flush();
            }
            if (!child.waitFor(90, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IllegalStateException("Timed out independent HTTP client");
            }
            String childSummary = Files.readString(childLog);
            assertEquals(0, child.exitValue(),
                    "Independent client failed (no credentials in client log): " + childSummary);
            assertTrue(childSummary.contains("INDEPENDENT_CLIENT rows=6000"),
                    "Missing independent-client completion evidence");
            assertEquals(6000, SERVER_TIMINGS.size(),
                    "Exactly one server measurement per independent client sequence");
            try (var output = Files.newBufferedWriter(serverCsv, StandardCharsets.UTF_8)) {
                output.write("sequence,server_start_offset_ms,server_elapsed_ms");
                output.newLine();
                for (int sequence = 1; sequence <= REQUESTS; sequence++) {
                    ServerTiming timing = SERVER_TIMINGS.get(sequence);
                    assertNotNull(timing, "Server missed synthetic sequence " + sequence);
                    assertTrue(Double.isFinite(timing.elapsedMs()) && timing.elapsedMs() >= 0);
                    output.write(String.format(Locale.ROOT, "%d,%.6f,%.6f",
                            sequence, timing.startOffsetMs(), timing.elapsedMs()));
                    output.newLine();
                }
            }
            System.out.println(childSummary.strip());
            System.out.printf("INDEPENDENT_LAB client_csv=%s server_csv=%s"
                    + " child_pid=%d server_samples=%d%n",
                    childCsv, serverCsv, child.pid(), SERVER_TIMINGS.size());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

}
