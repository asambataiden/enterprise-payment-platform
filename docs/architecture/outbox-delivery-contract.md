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
