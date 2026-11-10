package dev.asambataiden.payment.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic connection pool contention test, not a load benchmark.
 * Only this isolated Testcontainers application context has a two-slot pool.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.validation-timeout=250"
})
class HikariPoolSaturationIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    HikariDataSource dataSource;

    @Test
    void exhaustedPoolRejectsWithinConfiguredTimeoutAndRecoversAfterRelease() throws Exception {
        assertEquals(2, dataSource.getMaximumPoolSize());
        assertEquals(500, dataSource.getConnectionTimeout());

        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = (java.util.concurrent.Callable<Void>) () -> {
                try (var connection = dataSource.getConnection()) {
                    assertTrue(connection.isValid(2));
                    acquired.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS),
                            "Test must always release the held connection");
                }
                return null;
            };
            var first = workers.submit(holder);
            var second = workers.submit(holder);
            try {
                assertTrue(acquired.await(10, TimeUnit.SECONDS), "Two pool leases must be held");
                assertEquals(2, dataSource.getHikariPoolMXBean().getActiveConnections());

                long started = System.nanoTime();
                SQLException exhausted = assertThrows(SQLException.class, () -> {
                    try (var unexpected = dataSource.getConnection()) {
                        fail("A third connection must not be granted while both slots are held");
                    }
                });
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertNotNull(exhausted);
                assertTrue(elapsedMillis < 5_000,
                        "Connection acquisition must fail within a bounded period; observed "
                                + elapsedMillis + " ms");
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        try (var recovered = dataSource.getConnection();
             var statement = recovered.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }
}
