package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentHistoryService;
import dev.asambataiden.payment.payments.application.PaymentSearchService;
import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.shared.observability.CorrelationIdFilter;
import dev.asambataiden.payment.shared.security.PaymentSecurityConfig;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * No simulation setting is provided: configuration defaults to fail-closed.
 * Ordinary merchant APIs must remain registered and operational.
 */
@WebMvcTest({PaymentController.class, SimulatedPaymentController.class})
@Import({CorrelationIdFilter.class, PaymentSecurityConfig.class})
class SimulationDisabledWebTest {
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;

    @MockitoBean PaymentService service;
    @MockitoBean PaymentSearchService search;
    @MockitoBean PaymentHistoryService history;
    @MockitoBean SimulatedPaymentTransitionService transitions;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void trainingOnlyRouteIsNotRegisteredWithoutExplicitOptIn() throws Exception {
        assertFalse(mappings.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.getPatternValues().stream()
                        .anyMatch(path -> path.endsWith("/simulated-transitions"))));

        mvc.perform(post("/api/v1/payments/{id}/simulated-transitions", UUID.randomUUID())
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:simulate")))
                        .header("Event-Id", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"targetState\":\"AUTHORIZED\"}"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(transitions);
    }

    @Test
    void normalMerchantSearchStillWorksWhenSimulationIsDisabled() throws Exception {
        when(search.search("merchant-a", null, null, 25, null))
                .thenReturn(new PaymentSearchService.SearchPage(List.of(), null, false));
        mvc.perform(get("/api/v1/payments")
                        .with(jwt().jwt(t -> t.claim("merchant_id", "merchant-a"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_payments:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments").isEmpty());
    }
}
