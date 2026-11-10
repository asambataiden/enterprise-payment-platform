package dev.asambataiden.payment.performance;

import com.zaxxer.hikari.HikariDataSource;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OPT-IN ONLY: invoke by explicit -Dtest=ConcurrentHttpLoadLab.
 * Not matched by the default *Test.java / *IT.java regression includes.
 * Uses a real loopback HTTP server, signed test JWTs and disposable Postgres.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "spring.datasource.hikari.maximum-pool-size=2",
            "spring.datasource.hikari.minimum-idle=0",
            "spring.datasource.hikari.connection-timeout=500",
            "spring.datasource.hikari.validation-timeout=250",
            "payment.simulation.enabled=false",
            "payment.messaging.kafka.enabled=false",
            "payment.messaging.kafka.consumer.enabled=false"
        })
@Import(ConcurrentHttpLoadLab.TrustedLabIssuer.class)
class ConcurrentHttpLoadLab {
    private static final String ISSUER = "https://load-lab.test.invalid";
    private static final String AUDIENCE = "enterprise-payment-service";
    private static final KeyPair KEY = makeKey();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @TestConfiguration(proxyBeanMethods = false)
    static class TrustedLabIssuer {
        @Bean
        JwtDecoder jwtDecoder() {
            var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY.getPublic()).build();
            decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators
                    .createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    HikariDataSource dataSource;

    @Autowired
    PaymentService payments;

    private static KeyPair makeKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize test-only signing key", ex);
        }
    }

    private static String token(String merchant) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).audience(AUDIENCE).subject("http-load-lab")
                .claim("merchant_id", merchant).claim("scope", "payments:read")
                .issueTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEY.getPrivate()));
        return jwt.serialize();
    }

    private record Sample(int httpStatus, double elapsedMs) {}

    private static double percentile(List<Double> values, double percentage) {
        if (values.isEmpty()) return 0;
        var ordered = new ArrayList<>(values);
        ordered.sort(Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(percentage * ordered.size()) - 1);
        return ordered.get(index);
    }

    private List<Sample> exercise(HttpClient client, URI url, String bearer,
                                  int concurrency, int total) throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(concurrency)) {
            List<Future<Sample>> futures = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                Callable<Sample> action = () -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var request = HttpRequest.newBuilder(url)
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", "Bearer " + bearer)
                            .GET().build();
                    long begin = System.nanoTime();
                    var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    return new Sample(response.statusCode(),
                            (System.nanoTime() - begin) / 1_000_000.0);
                };
                futures.add(workers.submit(action));
            }
            long wallStart = System.nanoTime();
            start.countDown();
            List<Sample> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            long wallElapsed = System.nanoTime() - wallStart;
            long ok = results.stream().filter(x -> x.httpStatus() == 200).count();
            long unavailable = results.stream().filter(x -> x.httpStatus() == 503).count();
            var ms = results.stream().map(Sample::elapsedMs).toList();
            System.out.printf(java.util.Locale.ROOT,
                    "HTTP_LOAD_LAB concurrent=%d requests=%d ok=%d unavailable=%d other=%d "
                    + "p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f wall_ms=%.3f observed_rps=%.2f%n",
                    concurrency, total, ok, unavailable, total - ok - unavailable,
                    percentile(ms, .50), percentile(ms, .95), percentile(ms, .99),
                    wallElapsed / 1_000_000.0, total * 1_000_000_000.0 / wallElapsed);
            return List.copyOf(results);
        }
    }

    @Test
    void saturatedAndRecoveredHttpRequestsAreBoundedAndTenantAuthenticated() throws Exception {
        String merchant = "load-lab-" + UUID.randomUUID();
        var created = payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("1.25"), "EUR", "synthetic-load-lab"));
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/v1/payments/" + created.id());
        String bearer = token(merchant);

        try (var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            // Warm-up also establishes that test-only JWTs are accepted through
            // the real HTTP filter chain, not through MockMvc principals.
            var warmup = exercise(http, endpoint, bearer, 2, 8);
            assertTrue(warmup.stream().allMatch(s -> s.httpStatus() == 200),
                    "Warm-up requests must be successful authenticated merchant reads");

            // Hold BOTH Hikari leases for the entire overload phase.
            // Genuine GETs must fail as sanitized 503 instead of hanging.
            try (var lease1 = dataSource.getConnection();
                 var lease2 = dataSource.getConnection()) {
                assertEquals(2, dataSource.getHikariPoolMXBean().getActiveConnections());
                var overloaded = exercise(http, endpoint, bearer, 12, 24);
                assertTrue(overloaded.stream().allMatch(s -> s.httpStatus() == 503),
                        "Every overloaded authenticated request must fail closed with 503");
            }

            // Once the pool is released, the same real HTTP path recovers.
            var recovered = exercise(http, endpoint, bearer, 8, 40);
            assertTrue(recovered.stream().allMatch(s -> s.httpStatus() == 200),
                    "The authenticated read endpoint must recover after lease release");
            assertTrue(dataSource.getHikariPoolMXBean().getTotalConnections() <= 2);
        }
    }
}
