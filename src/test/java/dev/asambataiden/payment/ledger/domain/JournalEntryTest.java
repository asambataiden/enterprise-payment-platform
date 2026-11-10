package dev.asambataiden.payment.ledger.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalEntryTest {
    private static LedgerPosting debit(String account, String amount) {
        return new LedgerPosting(account, PostingSide.DEBIT, new BigDecimal(amount));
    }

    private static LedgerPosting credit(String account, String amount) {
        return new LedgerPosting(account, PostingSide.CREDIT, new BigDecimal(amount));
    }

    private static JournalEntry entry(List<LedgerPosting> postings) {
        return new JournalEntry(UUID.randomUUID(), "merchant-a", UUID.randomUUID(), "EUR", postings);
    }

    @Test
    void acceptsBalancedTwoSidedEntry() {
        var entry = entry(List.of(debit("receivable", "10.00"), credit("clearing", "10.0")));
        assertEquals(2, entry.postings().size());
        assertEquals("merchant-a", entry.merchantId());
    }

    @Test
    void acceptsBalancedSplitCreditEntry() {
        var entry = entry(List.of(debit("receivable", "10.00"),
                credit("clearing", "8.00"), credit("fees", "2.00")));
        assertEquals(3, entry.postings().size());
    }

    @Test
    void rejectsUnbalancedJournalAndSinglePosting() {
        assertThrows(IllegalArgumentException.class,
                () -> entry(List.of(debit("receivable", "10.00"), credit("clearing", "9.99"))));
        assertThrows(IllegalArgumentException.class,
                () -> entry(List.of(debit("receivable", "10.00"))));
    }

    @Test
    void rejectsNonPositiveAmountAndExcessPrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> debit("receivable", "0.00"));
        assertThrows(IllegalArgumentException.class,
                () -> credit("clearing", "-1.00"));
        assertThrows(IllegalArgumentException.class,
                () -> debit("receivable", "1.001"));
    }

    @Test
    void enforcesMerchantCurrencyAndAccountIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> new JournalEntry(UUID.randomUUID(), " ", UUID.randomUUID(), "EUR",
                        List.of(debit("a", "1.00"), credit("b", "1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> new JournalEntry(UUID.randomUUID(), "merchant-a", UUID.randomUUID(), "eur",
                        List.of(debit("a", "1.00"), credit("b", "1.00"))));
        assertThrows(IllegalArgumentException.class,
                () -> debit(" ", "1.00"));
    }

    @Test
    void snapshotsPostingsAsUnmodifiableList() {
        var mutable = new ArrayList<>(List.of(debit("a", "1.00"), credit("b", "1.00")));
        var entry = entry(mutable);
        mutable.clear();
        assertEquals(2, entry.postings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> entry.postings().add(debit("c", "1.00")));
    }
}
