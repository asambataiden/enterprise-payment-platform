package dev.asambataiden.payment.shared.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
@AutoConfigureMockMvc
@Testcontainers
class PrometheusMetricsSecurityIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;

    @Test
    void anonymousMetricsRequestIsUnauthorized() throws Exception {
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentReadScopeCannotScrapeMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void dedicatedMetricsScopeCanReadActualPrometheusExposition() throws Exception {
        mvc.perform(get("/actuator/prometheus").with(jwt().authorities(
                        new SimpleGrantedAuthority("SCOPE_payments:metrics"))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(containsString("# HELP")));
    }
}
