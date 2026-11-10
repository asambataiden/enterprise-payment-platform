package dev.asambataiden.payment.payments.application;

import java.nio.charset.StandardCharsets;
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

    @Transactional(readOnly = true)
    public SearchPage search(String merchantId, String status, String merchantReference,
                             int limit, String cursor) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        if (merchantReference != null && (merchantReference.isBlank() || merchantReference.length() > 100)) {
            throw new IllegalArgumentException("merchantReference must contain 1 to 100 characters");
        }
        Position after = cursor == null ? null : decode(cursor);

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
                ? encode(new Position(items.getLast().createdAt(), items.getLast().id())) : null;
        return new SearchPage(items, nextCursor, hasMore);
    }

    private static String encode(Position position) {
        String raw = position.createdAt() + "|" + position.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor) {
        try {
            if (cursor.isBlank() || cursor.length() > 256) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid pagination cursor");
            }
            return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }
    }

    private record Position(Instant createdAt, UUID id) {}

    public record SearchPage(List<PaymentResult> payments, String nextCursor, boolean hasMore) {
        public SearchPage { payments = List.copyOf(payments); }
    }
}
