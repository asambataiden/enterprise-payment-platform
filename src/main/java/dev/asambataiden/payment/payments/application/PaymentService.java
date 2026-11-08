package dev.asambataiden.payment.payments.application;

import dev.asambataiden.payment.payments.application.port.PaymentStore;
import dev.asambataiden.payment.payments.domain.exception.IdempotencyConflictException;
import dev.asambataiden.payment.payments.domain.exception.PaymentNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private final PaymentStore store;

    public PaymentService(PaymentStore store) {
        this.store = store;
    }

    @Transactional
    public PaymentResult create(String key, CreatePaymentCommand command) {
        // The PostgreSQL adapter arbitrates concurrent inserts and returns
        // the winning row in the same transaction.
        PaymentResult stored = store.insertIfAbsentAndFind(key, command);

        // A reused key must never silently accept different payment terms.
        if (stored.amount().compareTo(command.amount()) != 0
                || !stored.currency().equals(command.currency())
                || !stored.merchantReference().equals(command.merchantReference())) {
            throw new IdempotencyConflictException();
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public PaymentResult get(UUID id) {
        return store.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
