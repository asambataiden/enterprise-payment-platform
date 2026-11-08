package dev.asambataiden.payment.ledger.application;

import dev.asambataiden.payment.ledger.domain.JournalEntry;
import dev.asambataiden.payment.ledger.domain.LedgerPosting;
import dev.asambataiden.payment.ledger.domain.PostingSide;
import dev.asambataiden.payment.payments.application.CreatePaymentCommand;
import dev.asambataiden.payment.payments.application.PaymentService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual PostgreSQL constraints; no in-memory approximation of accounting rules. */
@Testcontainers
@SpringBootTest
class SimulatedLedgerServiceIT {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    SimulatedLedgerService ledger;

    @Autowired
    PaymentService payments;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID payment(String merchant) {
        return payments.create(merchant, UUID.randomUUID().toString(),
                new CreatePaymentCommand(new BigDecimal("10.00"), "EUR", "journal-test"))
                .id();
    }

    private JournalEntry journal(String merchant, UUID paymentId) {
        return new JournalEntry(UUID.randomUUID(), merchant, paymentId, "EUR",
                List.of(new LedgerPosting("asset-clearing", PostingSide.DEBIT, new BigDecimal("10.00")),
                        new LedgerPosting("liability-suspense", PostingSide.CREDIT, new BigDecimal("10.00"))));
    }

    @Test
    void commitsBalancedJournalAndScopesRetrievalByMerchant() {
        UUID paymentId = payment("ledger-a");
        JournalEntry entry = journal("ledger-a", paymentId);
        ledger.append(UUID.randomUUID(), entry);

        assertEquals(entry, ledger.findByMerchantAndEntryId("ledger-a", entry.entryId()).orElseThrow());
        assertTrue(ledger.findByMerchantAndEntryId("ledger-b", entry.entryId()).isEmpty());
        assertEquals(Long.valueOf(2), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_postings WHERE entry_id = ?", Long.class, entry.entryId()));
    }

    @Test
    void duplicateEventPerMerchantCannotCreateSecondJournal() {
        UUID paymentId = payment("ledger-a");
        UUID eventId = UUID.randomUUID();
        JournalEntry original = journal("ledger-a", paymentId);
        ledger.append(eventId, original);

        assertThrows(DataIntegrityViolationException.class,
                () -> ledger.append(eventId, journal("ledger-a", paymentId)));
        assertEquals(Long.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE merchant_id = ? AND event_id = ?",
                Long.class, "ledger-a", eventId));
    }

    @Test
    void sameEventIdCanBeUsedByDifferentMerchantsWithoutDataSharing() {
        UUID eventId = UUID.randomUUID();
        JournalEntry first = journal("ledger-a", payment("ledger-a"));
        JournalEntry second = journal("ledger-b", payment("ledger-b"));
        ledger.append(eventId, first);
        ledger.append(eventId, second);
        assertNotEquals(first.entryId(), second.entryId());
        assertTrue(ledger.findByMerchantAndEntryId("ledger-a", second.entryId()).isEmpty());
        assertTrue(ledger.findByMerchantAndEntryId("ledger-b", first.entryId()).isEmpty());
    }

    @Test
    void cannotPostEntryToPaymentBelongingToAnotherMerchant() {
        UUID otherPayment = payment("ledger-a");
        JournalEntry forged = journal("ledger-b", otherPayment);
        assertThrows(DataIntegrityViolationException.class,
                () -> ledger.append(UUID.randomUUID(), forged));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE id = ?", Long.class, forged.entryId()));
    }

    @Test
    void databaseRejectsUpdateAndDeleteOfEntriesAndPostings() {
        JournalEntry entry = journal("ledger-a", payment("ledger-a"));
        ledger.append(UUID.randomUUID(), entry);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ledger_postings SET amount = 99.00 WHERE entry_id = ?", entry.entryId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM ledger_postings WHERE entry_id = ?", entry.entryId()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "DELETE FROM ledger_entries WHERE id = ?", entry.entryId()));
    }

    @Test
    void deferredDatabaseBalanceConstraintRejectsInvalidRawJournalOnCommit() {
        UUID ownerPayment = payment("ledger-a");
        UUID entryId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, merchant_id, payment_id, event_id, currency)
                    VALUES (?, ?, ?, ?, 'EUR')
                    """, entryId, "ledger-a", ownerPayment, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ledger_postings (entry_id, line_number, account_id, side, amount)
                    VALUES (?, 1, 'asset', 'DEBIT', 10.00), (?, 2, 'liability', 'CREDIT', 9.99)
                    """, entryId, entryId);
        }));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE id = ?", Long.class, entryId));
    }

    @Test
    void databaseRejectsJournalWithoutAnyPostingsOnCommit() {
        UUID entryId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status ->
                jdbc.update("""
                    INSERT INTO ledger_entries (id, merchant_id, payment_id, event_id, currency)
                    VALUES (?, ?, ?, ?, 'EUR')
                    """, entryId, "ledger-a", payment("ledger-a"), UUID.randomUUID())));
        assertEquals(Long.valueOf(0), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE id = ?", Long.class, entryId));
    }
}
