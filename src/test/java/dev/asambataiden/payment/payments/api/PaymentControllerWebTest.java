package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.PaymentSearchService;
import dev.asambataiden.payment.payments.application.PaymentHistoryService;
import java.util.List;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.payments.application.PaymentResult;
import java.math.BigDecimal;
import java.time.Instant;
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
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;

import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@Import({CorrelationIdFilter.class, PaymentSecurityConfig.class})
class PaymentControllerWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SimulatedPaymentTransitionService transitions;

    @MockitoBean
    private PaymentHistoryService history;

    @MockitoBean
    private PaymentSearchService search;

    @Test
    void merchantCanSearchWithReadScope() throws Exception {
        UUID id = UUID.randomUUID();
        when(search.search("merchant-a", "RECEIVED", "order-42", 10, null))
                .thenReturn(new PaymentSearchService.SearchPage(List.of(
                        new PaymentResult(id, new BigDecimal("12.50"), "EUR",
                                "order-42", "RECEIVED", Instant.parse("2026-11-10T00:00:00Z"))),
                        null, false));

        mvc.perform(get("/api/v1/payments")
                        .param("status", "RECEIVED")
                        .param("merchantReference", "order-42")
                        .param("limit", "10")
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].id").value(id.toString()))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void searchRejectsMissingScopeAndInvalidStatus() throws Exception {
        mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments")
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:write"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments").param("status", "NOT_REAL")
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:read"))))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(search);
    }

    @Test
    void readScopedMerchantCanViewPaymentHistory() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        var result = new PaymentResult(payment, new BigDecimal("12.50"), "EUR",
                "order-history", "AUTHORIZED", Instant.parse("2026-11-10T00:00:00Z"));
        when(history.history("merchant-a", payment)).thenReturn(
                new PaymentHistoryService.PaymentHistory(result, List.of(
                        new PaymentHistoryService.HistoryEvent(event, "AUTHORIZED",
                                Instant.parse("2026-11-10T00:00:01Z"), null, null)), false));
        mvc.perform(get("/api/v1/payments/{id}/history", payment)
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.id").value(payment.toString()))
                .andExpect(jsonPath("$.events[0].eventId").value(event.toString()))
                .andExpect(jsonPath("$.events[0].targetState").value("AUTHORIZED"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void paymentHistoryRequiresReadScopeAndMerchantIdentity() throws Exception {
        UUID payment = UUID.randomUUID();
        mvc.perform(get("/api/v1/payments/{id}/history", payment))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payments/{id}/history", payment)
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:write"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/{id}/history", payment)
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_payments:read"))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(history);
    }

    @Test
    void simulationScopeCanAdvanceOwnedPayment() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        when(service.get("merchant-a", payment)).thenReturn(
                new PaymentResult(payment, new BigDecimal("12.50"), "EUR",
                        "order-simulated", "AUTHORIZED", Instant.parse("2026-11-10T00:00:00Z")));
        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", payment)
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:simulate")))
                        .header("Event-Id", event.toString())
                        .contentType("application/json")
                        .content("{\"targetState\":\"AUTHORIZED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        org.mockito.Mockito.verify(transitions).advance(
                "merchant-a", payment, event, PaymentState.AUTHORIZED);
    }

    @Test
    void ordinaryWriteScopeCannotSimulateLifecycle() throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", UUID.randomUUID())
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:write")))
                        .header("Event-Id", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"targetState\":\"CAPTURED\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(transitions);
    }

    @Test
    void unauthenticatedSimulatedTransitionIsRejected() throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", UUID.randomUUID())
                        .header("Event-Id", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"targetState\":\"AUTHORIZED\"}"))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(transitions);
    }

    @Test
    void invalidTransitionReturnsStableConflictCode() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        org.mockito.Mockito.when(transitions.advance("merchant-a", payment, event, PaymentState.CAPTURED))
                .thenThrow(new IllegalStateException("Internal state must not leak"));
        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", payment)
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:simulate")))
                        .header("Event-Id", event)
                        .contentType("application/json")
                        .content("{\"targetState\":\"CAPTURED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_TRANSITION_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("Simulated payment transition rejected"));
    }

    @Test
    void simulationScopeWithoutMerchantIdentityIsRejected() throws Exception {
        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", UUID.randomUUID())
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_payments:simulate")))
                        .header("Event-Id", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"targetState\":\"AUTHORIZED\"}"))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(transitions);
    }

    @Test
    void responseIncludesServerGeneratedCorrelationId() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(service.get("merchant-a", missingId))
                .thenThrow(new PaymentNotFoundException(missingId));

        mvc.perform(get("/api/v1/payments/{id}", missingId)
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:read")))
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
        when(service.get("merchant-a", missingId))
                .thenThrow(new PaymentNotFoundException(missingId));

        mvc.perform(get("/api/v1/payments/{id}", missingId)
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void conflictingIdempotencyKeyReturnsProblemDetails() throws Exception {
        when(service.create(eq("merchant-a"), eq("already-used"), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IdempotencyConflictException());

        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:write")))
                        .header("Idempotency-Key", "already-used")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.code").value("PAYMENT_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void invalidPaymentPayloadReturnsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:write")))
                        .header("Idempotency-Key", "invalid-payload")
                        .contentType("application/json")
                        .content("""
                                {"amount":-1,"currency":"eur","merchantReference":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("PAYMENT_VALIDATION_FAILED"));
    }

    @Test
    void missingIdempotencyHeaderReturnsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:write")))
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unauthenticatedReadIsRejectedBeforeServiceInvocation() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
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
    void readScopeCannotCreatePayments() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:read")))
                        .header("Idempotency-Key", "read-only")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void writeScopeCannotReadPayments() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a")).authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:write"))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void unlistedApplicationRouteIsDenied() throws Exception {
        mvc.perform(get("/actuator/info").with(jwt().jwt(token -> token.claim("merchant_id", "merchant-a"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtMissingMerchantIdentityCannotReadPayment() throws Exception {
        mvc.perform(get("/api/v1/payments/{id}", UUID.randomUUID())
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:read"))))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    void jwtMissingMerchantIdentityCannotCreatePayment() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .with(jwt().authorities(
                                new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_payments:write")))
                        .header("Idempotency-Key", "missing-merchant")
                        .contentType("application/json")
                        .content("""
                                {"amount":10.00,"currency":"EUR","merchantReference":"order-123"}
                                """))
                .andExpect(status().isForbidden());
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
