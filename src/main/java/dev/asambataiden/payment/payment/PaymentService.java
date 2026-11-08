package dev.asambataiden.payment.payment;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PaymentService {
    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) { this.repository = repository; }

    @Transactional
    public PaymentResponse create(String key, CreatePaymentRequest request) {
        return repository.findByIdempotencyKey(key)
                .map(existing -> {
                    if (existing.getAmount().compareTo(request.amount()) != 0
                            || !existing.getCurrency().equals(request.currency())
                            || !existing.getMerchantReference().equals(request.merchantReference())) {
                        throw new ResponseStatusException(CONFLICT, "Idempotency key already used with different request");
                    }
                    return PaymentResponse.from(existing);
                })
                .orElseGet(() -> PaymentResponse.from(repository.saveAndFlush(
                        new Payment(key, request.amount(), request.currency(), request.merchantReference()))));
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        return repository.findById(id).map(PaymentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Payment not found"));
    }
}
