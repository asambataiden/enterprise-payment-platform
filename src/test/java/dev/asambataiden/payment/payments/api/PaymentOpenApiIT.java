package dev.asambataiden.payment.payments.api;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API contract served only when explicitly enabled with dedicated docs scope. */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class PaymentOpenApiIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    void generatedApiSpecDescribesOnlySupportedVersionedRoutesAndJwt() throws Exception {
        mvc.perform(get("/v3/api-docs")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_payments:docs"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.paths['/api/v1/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/payments/{id}'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/actuator/health']").doesNotExist());
    }

    @Test
    void documentationIsNotPublicAndRequiresDedicatedScope() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(jwt().authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_payments:read"))))
                .andExpect(status().isForbidden());
    }
    /**
     * Semantic compatibility snapshot: additive fields/descriptions are allowed,
     * but removing a v1 operation, documented response, schema field or required
     * input breaks the checked-in consumer contract.
     */
    @Test
    void generatedSpecPreservesReviewedV1Baseline() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")
                    .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                            "SCOPE_payments:docs"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var mapper = JsonMapper.builder().build();
        Map<?, ?> actual = mapper.readValue(json, Map.class);
        Map<?, ?> baseline;
        try (InputStream input = getClass().getResourceAsStream("/contracts/payment-api-v1-baseline.json")) {
            assertNotNull(input, "Missing reviewed API compatibility baseline");
            baseline = mapper.readValue(input, Map.class);
        }
        assertEquals(baseline.get("version"), object(actual, "info").get("version"));
        assertTrue(object(object(actual, "components"), "securitySchemes")
                .containsKey(baseline.get("scheme")), "Bearer scheme removed");

        var paths = object(actual, "paths");
        for (Object item : (List<?>) baseline.get("operations")) {
            Map<?, ?> operation = (Map<?, ?>) item;
            String path = (String) operation.get("path");
            String method = (String) operation.get("method");
            var responses = object(object(object(paths, path), method), "responses");
            for (Object code : (List<?>) operation.get("responses")) {
                assertTrue(responses.containsKey(code), "Breaking response removal: " + method + " " + path + " " + code);
            }
        }
        var schemas = object(object(actual, "components"), "schemas");
        for (Object item : (List<?>) baseline.get("schemas")) {
            Map<?, ?> contract = (Map<?, ?>) item;
            String name = (String) contract.get("name");
            var schema = object(schemas, name);
            var properties = object(schema, "properties");
            for (Object field : (List<?>) contract.get("properties")) {
                assertTrue(properties.containsKey(field), "Breaking schema removal: " + name + "." + field);
            }
            if (contract.containsKey("required")) {
                List<?> required = schema.get("required") instanceof List<?> fields ? fields : List.of();
                for (Object field : (List<?>) contract.get("required")) {
                    assertTrue(required.contains(field), "Missing required input: " + name + "." + field);
                }
            }
        }
    }

    private static Map<?, ?> object(Map<?, ?> source, String key) {
        Object value = source.get(key);
        assertInstanceOf(Map.class, value, "Expected OpenAPI object at " + key);
        return (Map<?, ?>) value;
    }
}
