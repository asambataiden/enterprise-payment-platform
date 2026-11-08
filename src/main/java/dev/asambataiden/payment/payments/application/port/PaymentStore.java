package dev.asambataiden.payment.payments.application.port;

import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentResult;
import java.util.Optional;
import java.util.UUID;

/** Persistence operations required by the payment use case. */
public interface PaymentStore {

    /**
     * Attempts an atomic insert, then returns the row associated with the key.
     * The implementation must use the caller's transaction.
     */
    PaymentResult insertIfAbsentAndFind(String merchantId, String idempotencyKey, CreatePaymentCommand command);

    Optional<PaymentResult> findByMerchantIdAndId(String merchantId, UUID paymentId);
}
