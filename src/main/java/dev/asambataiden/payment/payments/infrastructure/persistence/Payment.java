package dev.asambataiden.payment.payments.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {
    @Id
    private UUID id;
    @Column(name = "merchant_id", length = 128)
    private String merchantId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "merchant_reference", nullable = false, length = 100)
    private String merchantReference;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {}

    public Payment(String merchantId, String idempotencyKey, BigDecimal amount, String currency, String merchantReference) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.merchantReference = merchantReference;
        this.status = "RECEIVED";
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getMerchantReference() { return merchantReference; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
