package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.infrastructure.persistence.Payment;
import dev.asambataiden.payment.payments.infrastructure.persistence.PaymentRepository;
import dev.asambataiden.payment.payments.infrastructure.persistence.PaymentJdbcWriter;
import dev.asambataiden.payment.payments.api.dto.PaymentResponse;
import dev.asambataiden.payment.payments.api.dto.CreatePaymentRequest;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class PaymentService {
    private final PaymentRepository repository;
    private final PaymentJdbcWriter writer;

    public PaymentService(PaymentRepository repository, PaymentJdbcWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    @Transactional
    public PaymentResponse create(String key, CreatePaymentRequest request) {
        // PostgreSQL arbitrates concurrent inserts. A losing transaction waits for
        // the winner before ON CONFLICT returns no rows at READ COMMITTED isolation.
        Payment candidate = new Payment(key, request.amount(), request.currency(), request.merchantReference());
        writer.insertIfAbsent(candidate);
        Payment stored = repository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotent payment missing after insert attempt"));

        // A reused key with different data must never silently accept new terms.
        if (stored.getAmount().compareTo(request.amount()) != 0
                || !stored.getCurrency().equals(request.currency())
                || !stored.getMerchantReference().equals(request.merchantReference())) {
            throw new ResponseStatusException(CONFLICT, "Idempotency key already used with different request");
        }
        return PaymentResponse.from(stored);
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(UUID id) {
        return repository.findById(id).map(PaymentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Payment not found"));
    }
}
