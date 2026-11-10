package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.SimulatedPaymentTransitionService;
import dev.asambataiden.payment.payments.api.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learning-only API boundary. The endpoint does not exist unless the
 * deployment explicitly opts in. JWT simulation scope and merchant ownership
 * remain mandatory even when enabled.
 */
@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnProperty(prefix = "payment.simulation", name = "enabled", havingValue = "true")
public class SimulatedPaymentController {
    private final PaymentService payments;
    private final SimulatedPaymentTransitionService transitions;

    public SimulatedPaymentController(PaymentService payments,
                                      SimulatedPaymentTransitionService transitions) {
        this.payments = payments;
        this.transitions = transitions;
    }

    @Operation(summary = "Advance a training-only simulated payment lifecycle (not real funds movement)")
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
        String merchantId = PaymentController.merchantId(jwt);
        transitions.advance(merchantId, id, eventId, request.targetState());
        return PaymentResponse.from(payments.get(merchantId, id));
    }
}
