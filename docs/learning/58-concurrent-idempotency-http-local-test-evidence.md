# Chapter 58 — Concurrent signed-JWT retries: verified evidence and plain-language explanation

## The result first

On **2026-11-10 10:25 Europe/Berlin** the developer executed the
Chapter 57 verification command against Git revision
`f4d8eb18ca86ea8e763f5ef2e4d1a647694abb5a`:

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger
mvn -B --no-transfer-progress -f "$ROOT/pom.xml" \
    -Dit.test=PaymentBearerTokenIT verify
```

**Observed results in the provided local terminal log:**
- `maven-surefire-plugin`: **109 unit tests**, 0 failed, 0 errors, 0 skipped.
- `maven-failsafe-plugin`: **8 integration tests** in `PaymentBearerTokenIT`,
  0 failed, 0 errors, 0 skipped.
- Build finished with **`BUILD SUCCESS`** in 9.854 seconds.
- A disposable `postgres:17-alpine` container started with
  PostgreSQL 17.11.
- Flyway successfully applied migrations V1–V9.
- Java 25.0.5 and Testcontainers 2.0.5 were reported in the log.
- Latest checked [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42790264915)
  and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42790265006)
  passed on the same code SHA.

These are verified **test outcomes**, not real financial transactions.
The integration-test log reports the aggregate 8-test class result;
individual assertions are understood from the committed test source.

## Repeat execution — reproducibility check

A second developer-supplied local terminal log records an additional
execution on **2026-11-10 at 10:31 Europe/Berlin** using:

```bash
mvn -B --no-transfer-progress -f "$ROOT/pom.xml" \
    -Dit.test=PaymentBearerTokenIT verify
```

This repeat was performed after the previous successful targeted test.
The uploaded log shows no source modifications or fresh pull between
the two commands; it is therefore a repeat of the same checked-out
implementation, not independent evidence for a new commit.

**Observed repeat:**

| Evidence | Observed value |
| --- | --- |
| Surefire unit tests | 109, 0 failures, 0 errors, 0 skips |
| Failsafe `PaymentBearerTokenIT` | 8, 0 failures, 0 errors, 0 skips |
| Maven outcome | `BUILD SUCCESS` |
| Total build time | 8.374 seconds |
| Java | 25.0.5.0.3 |
| Disposable database | PostgreSQL 17.11, `postgres:17-alpine` |
| Schema | 9 Flyway migrations applied successfully |

**Beginner explanation:** rerunning a test checks that the outcome is
repeatable in the same environment. It does **not** prove absence of
all possible race conditions, and two passing runs are not a
statistical reliability guarantee. The logged `ERROR` entries in
`PaymentApiExceptionHandler` belong to intentional negative-path
unit tests; the test reports show zero failures. Never ignore an
unexpected error just because the overall build is green—inspect its
test context.

Both local runs support the correctness lesson in Chapter 57.
The six-stage performance results from Chapter 56 are a **different**
kind of evidence and should not be confused with idempotency
correctness tests.

## What happened? Everyday explanation

Imagine a shop accidentally sends the same order **eight times at
once** because its connection became unreliable. The system should
not record eight separate simulated payment entries. All eight
requests must resolve to the *same* record.

The Chapter 57 test combines eight synchronized Java workers, signed
JWTs (which identify the merchant), an identical idempotency key and
an identical request body. After the requests return, it checks
two things independently:

1. All eight HTTP responses reference **one payment location**.
2. A SQL query confirms **exactly one row** for the merchant and key.

Then it checks that a changed amount with the same key is rejected
with HTTP 409, and a different merchant receives HTTP 404 when
trying to read the record.

## Why two types of checks?

The HTTP Location assertions check what clients *see*.
The SQL COUNT assertion checks what the database *stored*.
Both matter: a system could return reassuring HTTP responses while
incorrectly writing duplicate records.

The database's unique constraint and atomic insert arbitration
protect the record even when requests overlap. The transaction
wraps the business operation, while payload comparison prevents
the same key being silently reused for a different amount.

## Why did Maven run 109 + 8 tests?

The command used `-Dit.test=PaymentBearerTokenIT verify`.
Maven's lifecycle runs unit tests (Surefire) before the
integration-test phase (Failsafe). The `it.test` selector restricts
the integration-test classes, not the ordinary Surefire suite.
Therefore the log correctly shows both 109 unit tests and 8
integration tests.

## What the passing test does NOT prove

- Production capacity, availability or latency service-level objectives
- Real-world payment authorization, settlement or refunds
- Every possible concurrency interleaving or outage
- Exactly-once end-to-end delivery across Kafka and external consumers
- Real identity-provider deployment, PCI DSS certification or a security audit

## Interview exercise

**Question:** Why do we need both a database uniqueness constraint
and an HTTP test for idempotency?

**Beginner answer:** The database prevents storing two entries
for the same merchant/key, and the HTTP test proves our real API
uses that protection even when many clients retry at once.

**Senior answer:** Application-only existence checks can race.
A composite database uniqueness constraint and atomic PostgreSQL
insert establish cross-thread and cross-instance arbitration.
The service validates payload equivalence, while end-to-end
integration testing verifies signed-token tenant scoping, controller
mapping, transactional persistence and the observable HTTP contract.

## Next step

Milestone 18 **Part A (concurrent retry correctness)** is verified.
Continue with **Part B** as a separate opt-in longer-duration
synthetic load experiment with safety thresholds and realistic
data distribution. Do not claim new performance measurements until
that lab actually runs.
