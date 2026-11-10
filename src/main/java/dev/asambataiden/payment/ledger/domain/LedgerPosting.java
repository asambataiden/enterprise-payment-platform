package dev.asambataiden.payment.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** One side of an immutable, single-currency simulated journal entry. */
public record LedgerPosting(String accountId, PostingSide side, BigDecimal amount) {
    public LedgerPosting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (accountId.isBlank() || accountId.length() > 128) {
            throw new IllegalArgumentException("Account identifier must contain 1–128 characters");
        }
        if (amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("Posting amount must be positive with at most two decimals");
        }
    }
}
