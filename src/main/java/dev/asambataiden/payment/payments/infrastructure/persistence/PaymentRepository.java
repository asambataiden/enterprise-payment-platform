package dev.asambataiden.payment.payments.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);
    Optional<Payment> findByMerchantIdAndId(String merchantId, UUID id);
}
