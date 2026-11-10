package dev.asambataiden.payment.payments.infrastructure.persistence;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentResult;
import dev.asambataiden.payment.payments.application.port.PaymentStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL persistence adapter. The unique index and INSERT ON CONFLICT
 * remain authoritative for concurrent idempotent requests.
 */
@Repository
public class PostgresPaymentStore implements PaymentStore {
    private final PaymentRepository repository;
    private final PaymentJdbcWriter writer;

    public PostgresPaymentStore(PaymentRepository repository, PaymentJdbcWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    @Override
    public PaymentResult insertIfAbsentAndFind(
            String merchantId, String idempotencyKey, CreatePaymentCommand command) {
        var candidate = new Payment(
                merchantId, idempotencyKey, command.amount(),
                command.currency(), command.merchantReference());
        writer.insertIfAbsent(candidate);
        var stored = repository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Idempotent payment missing after insert attempt"));
        return toResult(stored);
    }

    @Override
    public Optional<PaymentResult> findByMerchantIdAndId(String merchantId, UUID paymentId) {
        return repository.findByMerchantIdAndId(merchantId, paymentId).map(PostgresPaymentStore::toResult);
    }

    private static PaymentResult toResult(Payment payment) {
        return new PaymentResult(
                payment.getId(), payment.getAmount(), payment.getCurrency(),
                payment.getMerchantReference(), payment.getStatus(), payment.getCreatedAt());
    }
}
