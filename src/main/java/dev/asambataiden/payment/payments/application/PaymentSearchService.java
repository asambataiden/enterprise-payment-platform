package dev.asambataiden.payment.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant-isolated, keyset-paginated search of simulated payments.
 * The cursor is a position token, NOT an authorization mechanism.
 */
@Service
public class PaymentSearchService {
    private static final int MAX_PAGE_SIZE = 100;
    private final JdbcTemplate jdbc;

    public PaymentSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, timeout = 5)
    public SearchPage search(String merchantId, String status, String merchantReference,
                             int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (merchantReference != null && (merchantReference.isBlank() || merchantReference.length() > 100)) {
            throw new IllegalArgumentException("merchantReference must contain 1 to 100 characters");
        }
        String queryFingerprint = fingerprint(merchantId, status, merchantReference);
        Position after = cursor == null ? null : decode(cursor, queryFingerprint);

        // Immutable creation time and unique ID make this a stable keyset
        // even when payment statuses change. All predicates are bound values.
        var sql = new StringBuilder("""
                SELECT id, amount, currency, merchant_reference, status, created_at
                FROM payments WHERE merchant_id = ?
                """);
        var args = new java.util.ArrayList<Object>();
        args.add(merchantId);
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (merchantReference != null) {
            sql.append(" AND merchant_reference = ?");
            args.add(merchantReference);
        }
        if (after != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            args.add(java.sql.Timestamp.from(after.createdAt()));
            args.add(after.id());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);

        List<PaymentResult> fetched = jdbc.query(sql.toString(), (rs, row) -> new PaymentResult(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("merchant_reference"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), args.toArray());

        boolean hasMore = fetched.size() > limit;
        List<PaymentResult> items = List.copyOf(fetched.subList(0, Math.min(limit, fetched.size())));
        String nextCursor = hasMore
                ? encode(new Position(items.getLast().createdAt(), items.getLast().id()), queryFingerprint) : null;
        return new SearchPage(items, nextCursor, hasMore);
    }

    /**
     * The v1 cursor binds its position to the validated merchant and filters.
     * This is an integrity check for user mistakes, not an authentication
     * mechanism: every SQL query still enforces merchant ownership.
     */
    private static String fingerprint(String merchantId, String status, String merchantReference) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            String canonical = merchantId.length() + ":" + merchantId
                    + "|" + (status == null ? "-1:" : status.length() + ":" + status)
                    + "|" + (merchantReference == null ? "-1:" :
                            merchantReference.length() + ":" + merchantReference);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", error);
        }
    }

    private static String encode(Position position, String fingerprint) {
        String raw = "v1|" + position.createdAt() + "|" + position.id() + "|" + fingerprint;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor, String expectedFingerprint) {
        try {
            if (cursor.isBlank() || cursor.length() > 256) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            String value = new String(bytes, StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])
                    || !MessageDigest.isEqual(parts[3].getBytes(StandardCharsets.US_ASCII),
                            expectedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            Instant createdAt = Instant.parse(parts[1]);
            UUID id = UUID.fromString(parts[2]);
            if (createdAt.isAfter(Instant.now().plusSeconds(60))) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            return new Position(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid pagination cursor", error);
        }
    }

    private record Position(Instant createdAt, UUID id) {}

    public record SearchPage(List<PaymentResult> payments, String nextCursor, boolean hasMore) {
        public SearchPage { payments = List.copyOf(payments); }
    }
}
