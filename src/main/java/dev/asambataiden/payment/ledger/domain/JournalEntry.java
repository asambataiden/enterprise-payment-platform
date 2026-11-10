package dev.asambataiden.payment.ledger.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced immutable simulated journal entry, not a settlement instruction.
 * Currency and tenant ownership are explicit and mandatory.
 */
public record JournalEntry(
        UUID entryId, String merchantId, UUID paymentId,
        String currency, List<LedgerPosting> postings) {
    public JournalEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(postings, "postings");
        if (merchantId.isBlank() || merchantId.length() > 128) {
            throw new IllegalArgumentException("Merchant ID is required");
        }
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be a three-letter uppercase ISO-style code");
        }
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A journal entry requires at least two postings");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (LedgerPosting posting : postings) {
            Objects.requireNonNull(posting, "Posting must not be null");
            if (posting.side() == PostingSide.DEBIT) {
                debits = debits.add(posting.amount());
            } else {
                credits = credits.add(posting.amount());
            }
        }
        if (debits.signum() == 0 || debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Journal entry must balance: debits equal credits");
        }
    }
}
