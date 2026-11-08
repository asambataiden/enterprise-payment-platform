package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentService service;

    @Test
    void unknownPaymentReturnsProblemDetails() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(service.get(missingId))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

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
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT, "Idempotency key already used with different request"));

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
}
