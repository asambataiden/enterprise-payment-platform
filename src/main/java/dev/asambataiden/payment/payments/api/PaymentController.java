package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.PaymentHistoryService;
import dev.asambataiden.payment.payments.api.dto.PaymentHistoryResponse;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.domain.PaymentState;
import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.api.dto.PaymentResponse;
import dev.asambataiden.payment.payments.api.dto.CreatePaymentRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {
    private final PaymentService service;
    private final SimulatedPaymentTransitionService transitions;
    private final PaymentHistoryService history;

    public PaymentController(PaymentService service, SimulatedPaymentTransitionService transitions,
                             PaymentHistoryService history) {
        this.service = service;
        this.transitions = transitions;
        this.history = history;
    }

    @Operation(summary = "Create or safely retry a simulated payment",
            description = "Merchant ownership comes from a validated JWT; the idempotency key is tenant-scoped.")
    @ApiResponse(responseCode = "201", description = "Payment recorded or matching retry")
    @ApiResponse(responseCode = "400", description = "Invalid request or idempotency header")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "Insufficient scope or merchant identity")
    @ApiResponse(responseCode = "409", description = "Same key reused with different terms")
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @Parameter(description = "Unique per merchant and request terms; maximum 128 characters", required = true)
            @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (key.length() > 128) { throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key must be <= 128 characters"); }
        PaymentResponse payment = PaymentResponse.from(service.create(merchantId(jwt), key,
                new CreatePaymentCommand(request.amount(), request.currency(), request.merchantReference())));
        return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.id())).body(payment);
    }

    @Operation(summary = "Get a simulated payment owned by the authenticated merchant")
    @ApiResponse(responseCode = "200", description = "Merchant-owned payment")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "Insufficient scope or merchant identity")
    @ApiResponse(responseCode = "404", description = "Payment not found for this merchant")
    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return PaymentResponse.from(service.get(merchantId(jwt), id));
    }

    @Operation(summary = "Read bounded simulated transition history of an owned payment",
            description = "Returns up to 100 newest committed transitions. hasMore=true means older entries are omitted. No settlement proof.")
    @ApiResponse(responseCode = "200", description = "Merchant-owned payment history")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "Insufficient read scope or merchant identity")
    @ApiResponse(responseCode = "404", description = "Payment not owned by authenticated merchant")
    @GetMapping("/{id}/history")
    public PaymentHistoryResponse history(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return PaymentHistoryResponse.from(history.history(merchantId(jwt), id));
    }

    /**
     * Explicit learning-only lifecycle control. Requires an independent
     * payments:simulate authority; ordinary write authority is insufficient.
     * Event-Id identifies one logical command and must be reused on retries.
     */
    @Operation(summary = "Advance a simulated payment lifecycle (not real funds movement)")
    @ApiResponse(responseCode = "200", description = "Updated or safely retried simulated payment")
    @ApiResponse(responseCode = "400", description = "Invalid event ID or target state")
    @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    @ApiResponse(responseCode = "403", description = "Missing simulation scope or merchant identity")
    @ApiResponse(responseCode = "404", description = "Payment not owned by authenticated merchant")
    @ApiResponse(responseCode = "409", description = "Invalid state transition or reused event ID")
    @PostMapping("/{id}/simulated-transitions")
    public PaymentResponse simulateTransition(
            @PathVariable UUID id,
            @RequestHeader("Event-Id") UUID eventId,
            @Valid @RequestBody SimulatedTransitionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        String merchant = merchantId(jwt);
        try {
            transitions.advance(merchant, id, eventId, request.targetState());
        } catch (IllegalStateException invalidTransition) {
            // Do not expose internal state or database diagnostic messages.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulated payment transition rejected");
        }
        return PaymentResponse.from(service.get(merchant, id));
    }

    private static String merchantId(Jwt jwt) {
        String merchantId = jwt == null ? null : jwt.getClaimAsString("merchant_id");
        if (merchantId == null || merchantId.isBlank() || merchantId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant identity is required");
        }
        return merchantId;
    }
}
