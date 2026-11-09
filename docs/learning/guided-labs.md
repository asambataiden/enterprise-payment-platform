# Guided labs — build the Enterprise Payment Platform from zero

These are **explanations and experiments against the existing source**. They do not require recreating the application in a new repository. Study the actual implementation history and trace it from the beginning; create temporary scratch branches for experiments if desired. Never store real credentials, tokens or payment details.

## Setup and verification prerequisites

- Java **25** and Maven **3.9+**
- Docker Desktop/compatible Docker API accessible to Testcontainers
- Git, an IDE and a local PostgreSQL option (Docker Compose is included)
- `git switch feat/payment-ledger` and `git pull --ff-only origin feat/payment-ledger`
- `mvn -B clean verify` to reproduce all unit and Testcontainers integration tests
- To run the service directly, set a **strong local-only** `DB_PASSWORD` and configure a trusted OAuth2/JWT issuer and audience; never bypass security by hardcoding tokens.

Do not activate Kafka in the normal local runtime just to run the tests. Broker integration tests provision ephemeral Kafka containers.

## Lab 00 — The problem before the framework

**What:** describe UC-01 and UC-02 in plain English: store a merchant's payment intake exactly once for a given merchant/key/terms and return only that merchant's records.

**Why:** interviewers assess your problem framing and quality requirements before framework vocabulary. Define idempotency, tenant isolation, correctness and recovery as non-functional priorities. Read the [roadmap](../roadmap/enterprise-payment-platform.md) and ADR-0001.

**Exercise:** Write acceptance criteria in Given/When/Then style for two identical requests, a changed-payload retry, and a cross-merchant read. No SQL or Java yet.

**Interview checkpoint:** Why a modular monolith? What is the smallest viable transaction boundary?

## Lab 01 — Java and HTTP boundaries

**What:** locate `PaymentController`, the request DTO/response DTO, `PaymentService`, `PaymentStore`, and Spring application bootstrap. Sketch the call chain.

**Why:** domain logic should not be implemented inside HTTP handlers; adapters translate requests into application commands. Identify where Jakarta Bean Validation occurs and how HTTP errors become RFC 9457 Problem Details.

**Exercise:** Trace one successful POST from controller through store to response. Trace one malformed amount or missing key. Write down whether each validation is API, domain or DB-side, and identify any gaps.

**Interview checkpoint:** Explain DTO vs command vs domain record; why validation at multiple layers may be appropriate.

## Lab 02 — PostgreSQL, Flyway and exact money representation

**What:** read V1 migration and `PaymentService`, `PaymentJdbcWriter`, `JournalEntry`. Observe `BigDecimal` and SQL numeric types.

**Why:** floating-point money is error-prone; durable schema evolution must be reviewed and versioned; DB constraints are the final line of defense when Java constructors are bypassed.

**Exercise:** Predict the outcome of inserting decimal 10.00 and 9.99 into a balanced journal. Find the migration and test that verifies commit failure. Remember that the current currency logic assumes at most two decimal places and is **not** globally currency-correct.

**Interview checkpoint:** Why Flyway instead of `ddl-auto=update`? Why `BigDecimal` and schema constraints?

## Lab 03 — Idempotency under concurrency

**What:** read ADR-0002, `PaymentStore` and the PostgreSQL `INSERT ... ON CONFLICT` operation in the writer.

**Why:** a SELECT-first design races on two nodes. Uniqueness at the DB boundary is atomic even if multiple application instances compete.

**Exercise:** Draw two simultaneous requests sharing a key; follow `PaymentServiceIT`. Explain why a duplicate with different amount must return a conflict rather than a successful replay.

**Interview checkpoint:** Distinguish retry safety, duplicate suppression, consistency and exactly-once execution.

## Lab 04 — JWT and tenant isolation

**What:** read security configuration, `PaymentController.merchantId`, V2 ownership upgrade and `PaymentBearerTokenIT`.

**Why:** OAuth2 authentication answers *who sent the request*; authorization and tenant scoping answer *which resources they can access*. A merchant ID supplied directly by a client is not proof of merchant ownership.

**Exercise:** Find tests for wrong audience, wrong issuer, invalid signature, missing scope, missing merchant ID and attempted cross-merchant read. Explain the 404 for a foreign payment.

**Interview checkpoint:** What is the difference between trusted claims, headers, scope, roles and DB isolation? What is not proven without a real IdP?

## Lab 05 — Secure engineering feedback loop

**What:** study ECS logs/correlation, RFC 9457 errors, GitHub Java CI, Gitleaks, Trivy, ArchUnit and [PR #1 readiness](../reviews/pr-0001-release-readiness.md).

**Why:** correctness requires repeatable validation, actionable failure diagnostics and a clear separation between *passing scanners* and *actual production security controls*.

**Exercise:** Run full Maven verify and inspect Surefire/Failsafe test classes. Map each test family to one quality attribute. Read the GitHub workflow configs to see what scanning is and is not performed.

**Interview checkpoint:** How do you justify release gates and explain scan limitations without overstating maturity?

## Lab 06.1 — Pure domain rules

**What:** read `PaymentStateMachine`, `JournalEntry` and `LedgerPosting`.

**Why:** make forbidden transitions and imbalanced postings unrepresentable through normal domain construction, with fast and focused tests.

**Exercise:** Reason about RECEIVED → CAPTURED directly; check the rejection test. Compare debit 10.0 and credit 10.00, then debit 10.00 and credit 9.99.

**Interview checkpoint:** State machine vs boolean flags. Why is the domain pure and independent of Kafka?

## Lab 06.2 — Durable ledger invariants

**What:** trace V3's `ledger_entries`, `ledger_postings`, balance trigger and finalization, then `SimulatedLedgerServiceIT`.

**Why:** database constraints defend against bypasses and multi-instance concurrency; journals are append-only after finalization. The simulation is not regulated accounting.

**Exercise:** Predict a forged cross-merchant posting, a late insert, and commit without postings. Check the PostgreSQL SQLSTATE tests.

**Interview checkpoint:** DB constraints vs application validation; why use deferred triggers at commit?

## Lab 06.3 — One ACID transaction

**What:** read V4 and `SimulatedPaymentTransitionService.advance`, then its integration tests.

**Why:** updating status alone while failing to write a journal or outbox creates an inconsistent system. `FOR UPDATE` serializes transitions on a merchant-owned payment.

**Exercise:** Trace AUTHORIZED → CAPTURED; force a duplicate event conflict (in the test) and verify the state rolls back. Explain why the capture journal derives the amount from stored data.

**Interview checkpoint:** Local ACID vs distributed 2PC; why row locks here?

## Lab 06.4 — Transactional outbox and inbox

**What:** read V5, `PaymentOutboxDispatcher`, `PaymentInbox` and corresponding tests.

**Why:** broker outages must not hold the payment transaction open; the relay claims committed events separately. Broker delivery can be duplicated, so consumers need idempotency.

**Exercise:** Draw an ACK lost after publish. Reason about lease fencing and second-worker claims. Observe test cases for backoff, stale ACK, claim concurrency and inbox rollback.

**Interview checkpoint:** Why 'at least once' rather than 'exactly once'?

## Lab 06.5 — Poison events and bounded supervision

**What:** read V6, `SupervisedPaymentOutboxWorker`, `TrustedPaymentEventConsumer` and integration tests.

**Why:** infinite retry loops and duplicate local effects can cause outage amplification and inconsistent downstream state.

**Exercise:** Find the fifth-attempt quarantine test. Explain how an operator would safely investigate and replay an event; note this administrative workflow is **not implemented yet**.

**Interview checkpoint:** What are poison-event operational runbooks? Why is a simple bounded worker not the same as a supervised scheduler?

## Lab 06.6 — The actual Kafka boundary

**What:** read `KafkaPaymentEventPublisher`, optional configuration, `KafkaPaymentEventPublisherIT`, `KafkaOutboxEndToEndIT` and the [Kafka contract](../architecture/kafka-integration-contract.md).

**Why:** the publisher must prove broker acceptance and preserve useful per-payment ordering without coupling state changes to a broker transaction.

**Exercise:** Explain how the Testcontainers broker is created, where topic partitions come from, why the Kafka key includes the merchant and payment, and where `dispatched_at` is set. Compare the local test broker's PLAINTEXT connection with deployment's required SSL/SASL configuration. Reconstruct the CI failure where `apache/kafka-native:4.1.0` crashed and the test switched to JVM-based `apache/kafka:4.1.2`.

**Interview checkpoint:** Explain `acks=all`, idempotent producer, partition ordering, retry duplicates, and why a broker ACK is not a consumer ACK.

## Lab 06.7+ — Design challenges, not yet implemented

1. Authenticated cross-service Kafka listener with schema compatibility and idempotent consumer-owned DB transactions.
2. Publisher lifecycle, metrics, alerts, secure TLS/SASL smoke test, permissions and secret injection.
3. Accounting reversals, reconciliation and realistic simulation accounting semantics.
4. Load/chaos and disaster recovery; threat modeling, SBOM and artifact signing.

**Always label these as planned in interviews, never as delivered functionality.**

## Completion evidence

For every lab, fill in:

```text
Lab ID:
Question:
Why this design:
Call path / sequence:
Failure scenario:
Classes and migrations inspected:
Test class and exact assertion:
mvn verify result and date:
Git commit / CI link:
What is implemented:
Known limitations:
30-second interview explanation:
```

Keep completed notes in `docs/learning/notes/` only when they contain original reasoning and evidence, rather than copying all code. Use [interview workbook](interview-workbook.md) to rehearse.
