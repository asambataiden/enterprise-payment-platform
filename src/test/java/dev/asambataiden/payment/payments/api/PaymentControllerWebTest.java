package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.shared.observability.CorrelationIdFilter;
import dev.asambataiden.payment.shared.security.PaymentSecurityConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithAnonymousUser;
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@Import({CorrelationIdFilter.class, PaymentSecurityConfig.class})
@WithMockUser(authorities = {"SCOPE_payments:read", "SCOPE_payments:write"})
class PaymentControllerWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void responseIncludesServerGeneratedCorrelationId() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(service.get(missingId))
                .thenThrow(new PaymentNotFoundException(missingId));

        mvc.perform(get("/api/v1/payments/{id}", missingId)
                        .header(CorrelationIdFilter.HEADER_NAME, "untrusted-value"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(CorrelationIdFilter.HEADER_NAME))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME,
                        org.hamcrest.Matchers.not("untrusted-value")))
                .andExpect(header().string(CorrelationIdFilter.HEADER_NAME,
                        org.hamcrest.Matchers.matchesPattern(
                                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    void unknownPaymentReturnsProblemDetails() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(service.get(missingId))
                .thenThrow(new PaymentNotFoundException(missingId));

        mvc.perform(get("/api/v1/payments/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    @Test
    void conflictingIdempotencyKeyReturnsProblemDetails() throws Exception {
        when(service.create(eq("already-used"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IdempotencyConflictException());

        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "already-used")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void invalidPaymentPayloadReturnsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "invalid-payload")
                        .contentType("application/json")
                        .content("""
                                {"amount":-1,"currency":"eur","merchantReference":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void missingIdempotencyHeaderReturnsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @WithAnonymousUser
    void unauthenticatedReadIsRejectedBeforeServiceInvocation() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @WithAnonymousUser
    void unauthenticatedCreateIsRejectedBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "anonymous")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "SCOPE_payments:read")
    void readScopeCannotCreatePayments() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "read-only")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(authorities = "SCOPE_payments:write")
    void writeScopeCannotReadPayments() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void unlistedApplicationRouteIsDenied() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
    }
}
