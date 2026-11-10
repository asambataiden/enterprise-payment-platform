# Chapter 43 — PostgreSQL Query Access Paths and Bounded Read Operations

## Objective and baseline

Milestone 12 assesses the database operations actually exercised by the
authenticated merchant API, rather than introducing speculative caching
or index proliferation. The preceding milestone passed 181 local tests,
plus Java and Security CI, on commit `bb038f1b`.

**This chapter does not claim production benchmark results.** The current
CI workload runs on small disposable PostgreSQL 17 Testcontainers
databases, not a representative production dataset.

## SQL inventory

| Use case | Core predicates | Ordering / bound | Before V9 |
| --- | --- | --- | --- |
| Merchant payment search | `payments.merchant_id = ?`, optional exact `status` / `merchant_reference`, optional keyset tuple | `created_at DESC, id DESC LIMIT 1..101` | Existing merchant ID index does not directly provide requested order |
| Payment history | `payment_outbox.merchant_id = ? AND payment_id = ?` | `created_at DESC, id DESC LIMIT 101` | Existing pending/claim indexes serve dispatch, not merchant history |
| Single payment lookup | `merchant_id = ? AND id = ?` | one row | Existing unique owner-ID index remains appropriate |
| Idempotent creation | merchant + idempotency key | one row | Existing uniqueness enforcement remains appropriate |
| Outbox dispatch | pending / claim state and next attempt | bounded batch | Existing partial delivery indexes remain untouched |

## Chosen database changes

Flyway V9 creates exactly two targeted indexes:

```sql
CREATE INDEX idx_payments_merchant_created_id
    ON payments (merchant_id, created_at DESC, id DESC);

CREATE INDEX idx_outbox_merchant_payment_created_id
    ON payment_outbox (merchant_id, payment_id, created_at DESC, id DESC);
```

The primary merchant search index supports both first-page and
keyset-continuation access in the requested order. The history index
supports locating and ordering events for **one merchant-owned
payment**. The history lookup still uses its existing ledger-event
equality join; no ledger schema change is needed.

Do not remove existing indexes merely because some columns overlap.
Before pruning any index, inspect ownership, uniqueness, foreign-key
and dispatcher needs, plus actual read/write workload. Indexes consume
storage and amplify write cost.

## Query boundedness and pool configuration

- `PaymentSearchService.search` and `PaymentHistoryService.history`
  use `@Transactional(readOnly = true, timeout = 5)`. Spring's
  transaction timeout is applied to supported JDBC statements through
  transaction-aware Spring JDBC; it is not a guarantee of a 5-second
  end-to-end HTTP latency SLO.
- Hikari acquisition timeout is configurable via
  `DB_CONNECTION_TIMEOUT_MS` (default 3000).
- Pool validation timeout via `DB_VALIDATION_TIMEOUT_MS` (default 2000).
- Maximum connections via `DB_POOL_MAX_SIZE` (default 10). Set a
  deployment-wide budget across application replicas and migrations.

These are boundedness defaults for the training project, **not
validated capacity-planning recommendations**. Actual values should
be selected using load, saturation and deployment resource constraints.
A failing pool can signal resource exhaustion and should surface a
sanitized availability error rather than raw database diagnostics.
No automatic retry is added inside transactions.

## EXPLAIN and measurement method

A PostgreSQL integration regression checks that the V9 payment
search index exists and that an ordered index access path is possible
when sequential scans are disabled for one test transaction. This
test confirms index accessibility, **not** that the planner will
choose it for a tiny table or that it is faster.

For a useful before/after measurement, create synthetic data in an
isolated benchmark environment with representative:
- rows per merchant and merchant-count skew
- status frequencies and reference selectivity
- page sizes, first pages and deep continuation pages
- background writes/updates and autovacuum state

Then run `ANALYZE` and measure:

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, amount, currency, merchant_reference, status, created_at
FROM payments
WHERE merchant_id = 'synthetic-merchant-001'
ORDER BY created_at DESC, id DESC
LIMIT 26;
```

Repeat with status/reference filters and a keyset predicate; record
median and p95 latencies, scanned rows, shared buffer reads/hits,
sort operators, and insert/update overhead. Avoid `EXPLAIN ANALYZE`
with sensitive real customer data or production-impacting operations
without an approved performance window and observability controls.
No invented performance numbers should enter the portfolio.

## Trade-offs

1. Indexing mutable, low-cardinality `status` without query
   selectivity evidence can harm update throughput.
2. An index alone does not produce snapshot-consistent pagination
   when status changes between requests.
3. Read timeouts do not make failed payment writes safe to retry;
   original idempotency keys and event IDs remain essential.
4. `SELECT ... LIMIT 101` prevents unbounded result materialization
   but does not eliminate the need to analyze CPU and disk access.
5. Postgres connection pool sizing is bounded by DB instance
   connection capacity and all application replicas.

## Test and acceptance

- Flyway V9 applies after V1–V8 to PostgreSQL 17.
- `PaymentServiceIT` checks index definition and planner access path.
- Existing merchant search, tenant isolation, lifecycle history,
  outbox and ledger integration tests continue to pass.
- `mvn -B --no-transfer-progress clean verify`.
- Java CI and Security CI must succeed on the *final* commit.

Mark the milestone verified only after all gates pass.

## Interview questions

1. Why does `(merchant_id, id)` not fully support newest-first pagination?
2. What is a keyset predicate and why does it benefit from matching
   composite index order?
3. What is the difference between confirming an available index path
   and benchmarking real latency?
4. Why is a read-only transaction timeout not an HTTP deadline?
5. How do connection pool sizes multiply with application replicas?
6. Why might an extra index be harmful to the payment write path?
