# Chapter 03 — PostgreSQL, Flyway, money and concurrent idempotency

**Status:** Existing persistence and tests explained.

## Why decimal values?

Binary floating point cannot exactly represent many decimal fractions. Monetary simulation uses `BigDecimal` and PostgreSQL numeric types. This does **not** establish correct currency-specific minor-unit policies for every ISO currency; those rules remain a design gap.

## Why Flyway?

An ordered, versioned SQL history makes schema changes explicit and reproducible. Never edit an already-applied migration to alter a production schema; add a new forward migration and test upgrades from old data.

Trace `V1__create_payments.sql` and `V2__merchant_payment_ownership.sql`. The merchant ID and idempotency key form the uniqueness boundary, so two merchants can independently choose the same key without conflicting.

## The concurrency race

```mermaid
sequenceDiagram
  participant A as Node A
  participant B as Node B
  participant DB as PostgreSQL
  A->>DB: INSERT merchant M / key K
  B->>DB: INSERT merchant M / key K
  DB-->>A: row inserted
  DB-->>B: conflict / row not inserted
  B->>DB: SELECT existing merchant M / key K
  DB-->>B: existing record
  Note over B: Compare request terms; replay or 409
```

A preceding `SELECT` is not sufficient: both nodes can observe an absent row. Database uniqueness arbitrates the race at the durable boundary. This is retry-safe intake, not a guarantee of exactly-once execution of arbitrary downstream side effects.

## Hands-on

Read `PaymentJdbcWriter` and `PaymentService`; find the index and statement responsible for concurrency safety. Run `PaymentServiceIT` with a PostgreSQL Testcontainer, inspect test setup and the assertions for same-terms retry versus changed-terms conflict.

```bash
mvn -B --no-transfer-progress -Dit.test=PaymentServiceIT -DskipTests=false verify
```

Because this Maven invocation can run other configured phases, check the actual Failsafe report rather than assuming the selected class was the only work performed.

## Failure analysis

What happens on a crash before commit, after commit but before HTTP response, and on concurrent retries? Why is a database unique constraint a stronger concurrency defense than a JVM `synchronized` block in a scaled deployment?

**Interview answer:** Idempotency needs a durable identity of the request and an atomic arbitration point. We compare the resulting stored terms rather than returning success for a key reused with different intent.
