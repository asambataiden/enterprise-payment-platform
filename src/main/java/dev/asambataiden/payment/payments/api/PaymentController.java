package dev.asambataiden.payment.payments.api;

import dev.asambataiden.payment.payments.application.PaymentService;
import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.api.dto.PaymentResponse;
import dev.asambataiden.payment.payments.api.dto.CreatePaymentRequest;

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

    public PaymentController(PaymentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CreatePaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (key.length() > 128) { throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key must be <= 128 characters"); }
        PaymentResponse payment = PaymentResponse.from(service.create(merchantId(jwt), key,
                new CreatePaymentCommand(request.amount(), request.currency(), request.merchantReference())));
        return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return PaymentResponse.from(service.get(merchantId(jwt), id));
    }

    private static String merchantId(Jwt jwt) {
        String merchantId = jwt == null ? null : jwt.getClaimAsString("merchant_id");
        if (merchantId == null || merchantId.isBlank() || merchantId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant identity is required");
        }
        return merchantId;
    }
}
