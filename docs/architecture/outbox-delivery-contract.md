# Milestone 06.4 — Reliable simulated-payment event delivery

- **Status:** Broker-independent persistence and retry behavior implemented and PostgreSQL-tested
- **Scope:** Educational simulation only; no financial processor or external webhook is configured
- **Source:** `payment_outbox` created transactionally with the payment-state change
- **Delivery:** `PaymentOutboxDispatcher.dispatchOne()`, explicitly invoked by a future supervised worker

## Event envelope (schema version 1)

`PaymentOutboxEvent(schemaVersion=1, eventId, merchantId, paymentId, targetState)`.

The event contains no sensitive card details. A transport adapter must serialize this schema deterministically, enforce schema compatibility, authentication/TLS and tenant isolation. The current code has **no broker adapter or scheduled publisher**.

## Delivery semantics and transaction boundary

1. A short database transaction selects one due undispatched row with `FOR UPDATE SKIP LOCKED`, sets a random `lease_token`, increments `delivery_attempts`, and sets a 30-second `leased_until`.
2. Commit the claim **before** making an external call.
3. Invoke a `PaymentEventPublisher` adapter outside any database transaction.
4. After success, acknowledge only where `(merchant_id, event_id, lease_token)` still match. If a lease was replaced, a stale worker cannot acknowledge the new owner's claim.
5. After failure, release only the matching lease and schedule retry with exponential delay capped at 300 seconds. The outbox row is not deleted.

Publishing and marking the row dispatched are not atomic across database and broker. A worker can publish successfully then crash or lose its lease before acknowledgment, so duplicate delivery is **expected**. Delivery is *at least once only under assumptions of a continuously retried, eventually successful publisher and operational supervision*. Neither exactly-once external delivery nor guaranteed liveness is claimed.

The current retry loop is intentionally **manually invoked**. Operational deployments need a scheduler, restart/recovery behavior, alerting, dispatch throughput limits, configurable lease durations, dead-letter policy, poison-event handling and secure broker credentials. A slow publish beyond its lease may overlap a second worker; consumer-side idempotency is mandatory.

## Consumer inbox

`payment_inbox` has a unique key `(consumer_name, merchant_id, event_id)`. An application can call `PaymentInbox.register()` in the **same transaction** as its own local database effects. On a duplicate, it should skip those effects. If the local transaction rolls back, the inbox receipt rolls back too.

This local exactly-once *effect* technique does not make remote calls or downstream brokers transactional. A future consumer must validate event provenance, schema version, merchant ownership and any domain-specific authorization before registering or acting upon a message. The inbox helper does not perform authorization.

## Verification

- `PaymentOutboxDispatcherIT` exercises successful delivery and ACK, transport failure/backoff/retry, expired lease recovery and stale ACK fencing, inbox duplicate rejection and rollback, and concurrent claims.
- PostgreSQL Testcontainers and Flyway V5 run on Java 25.
- [Java CI #145](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42511949964): **82/82 tests passed**, 46 fast and 36 integration.
- [Security CI #59](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42511950008): both packaged-dependency vulnerability and Git-history secret scans passed.

## Next gate

Before connecting Kafka, add a supervised and instrumented publisher adapter; introduce a consumer that validates trusted message origin and performs local effects transactionally; test crash-after-publish, lease-expiry races, poison events and broker unavailability. Keep PR #2 in draft until reviewed.

## Milestone 06.5 — Opt-in runtime, quarantine, trusted local consumer

- Flyway V6 adds `dead_lettered_at` and a non-sensitive `last_failure_category`. After **five** failed transport attempts, an event is quarantined and excluded from normal claims. It is **not deleted**. An operator must review the cause and explicitly reset it; automatic redelivery of poisoned messages is unsafe.
- `SupervisedPaymentOutboxWorker` performs at most 1–100 claims per invocation and exposes in-process claimed-cycle/error counters. It is not a scheduler. No worker is launched at application startup, and no actual broker is wired.
- `TrustedPaymentEventConsumer` verifies merchant, event ID, payment ID and target state against the committed local outbox, then inserts an inbox receipt and executes a local-database-only effect in the **same transaction**. Duplicates are skipped; rollback also removes the receipt.
- This validation is **not** a replacement for transport authentication, authenticated broker identities or cross-service event provenance. A consumer in another database cannot query the producer's private outbox as its authority and requires a dedicated signed/authenticated event boundary.
- A delivery failure is not exposed as success. The dispatcher currently reports that work was claimed; backoff/quarantine details remain in PostgreSQL for monitoring.
- The lease remains fixed at 30 seconds. A production transport adapter must enforce publish timeouts shorter than the lease or add safe lease renewal; delayed publishes can still duplicate delivery.
- In-process counters reset on process restart. Production requires a metrics backend, alert thresholds (pending age, quarantined count, retry rate, delivery lag), worker health supervision and runbooks.

### Operations gate

Before enabling scheduled background delivery or a Kafka adapter, define secure Kafka identities and TLS, topic partitions and ordering by merchant/payment, consumer-group rollout, versioned schema compatibility, replay controls, trace propagation, bounded payloads and a documented dead-letter replay approval procedure. Do not treat these tests as validating Kafka itself.
