package dev.asambataiden.payment.ledger.application;

import dev.asambataiden.payment.ledger.domain.JournalEntry;
import dev.asambataiden.payment.ledger.domain.LedgerPosting;
import dev.asambataiden.payment.ledger.domain.PostingSide;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal simulated ledger port of entry. There is deliberately no HTTP or
 * event listener that exposes posting operations at this milestone.
 */
@Service
public class SimulatedLedgerService {
    private final JdbcTemplate jdbc;

    public SimulatedLedgerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomic append. Database enforces payment/merchant ownership, event uniqueness
     * and a balanced journal at commit. Any failure rolls back the whole journal.
     */
    @Transactional
    public void append(UUID eventId, JournalEntry entry) {
        if (eventId == null || entry == null) {
            throw new IllegalArgumentException("Event and journal are required");
        }

        jdbc.update("""
                INSERT INTO ledger_entries (id, merchant_id, payment_id, event_id, currency)
                VALUES (?, ?, ?, ?, ?)
                """, entry.entryId(), entry.merchantId(), entry.paymentId(), eventId, entry.currency());

        int line = 1;
        for (LedgerPosting posting : entry.postings()) {
            jdbc.update("""
                    INSERT INTO ledger_postings (entry_id, line_number, account_id, side, amount)
                    VALUES (?, ?, ?, ?, ?)
                    """, entry.entryId(), line++, posting.accountId(), posting.side().name(), posting.amount());
        }
        // Finalize in the same transaction. PostgreSQL rejects further postings.
        if (jdbc.update("UPDATE ledger_entries SET finalized = TRUE WHERE id = ? AND finalized = FALSE",
                entry.entryId()) != 1) {
            throw new IllegalStateException("Unable to finalize simulated journal");
        }
    }

    @Transactional(readOnly = true)
    public Optional<JournalEntry> findByMerchantAndEntryId(String merchantId, UUID entryId) {
        if (merchantId == null || merchantId.isBlank() || entryId == null) {
            throw new IllegalArgumentException("Merchant and entry ID are required");
        }
        var matches = jdbc.query("""
                SELECT id, merchant_id, payment_id, currency
                FROM ledger_entries
                WHERE merchant_id = ? AND id = ?
                """, (rs, row) -> new EntryHeader(
                        rs.getObject("id", UUID.class),
                        rs.getString("merchant_id"),
                        rs.getObject("payment_id", UUID.class),
                        rs.getString("currency")), merchantId, entryId);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        EntryHeader header = matches.getFirst();
        List<LedgerPosting> postings = jdbc.query("""
                SELECT account_id, side, amount FROM ledger_postings
                WHERE entry_id = ? ORDER BY line_number
                """, (rs, row) -> new LedgerPosting(
                        rs.getString("account_id"),
                        PostingSide.valueOf(rs.getString("side")),
                        rs.getBigDecimal("amount")), entryId);
        return Optional.of(new JournalEntry(
                header.id(), header.merchant(), header.paymentId(), header.currency(), postings));
    }

    private record EntryHeader(UUID id, String merchant, UUID paymentId, String currency) {}
}
