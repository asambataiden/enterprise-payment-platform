package dev.asambataiden.payment.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank String key,
            @Valid @RequestBody CreatePaymentRequest request) {
        if (key.length() > 128) { throw new ResponseStatusException(BAD_REQUEST, "Idempotency-Key must be <= 128 characters"); }
        PaymentResponse payment = service.create(key, request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + payment.id())).body(payment);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable UUID id) { return service.get(id); }
}
