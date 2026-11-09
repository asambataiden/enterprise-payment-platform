# Milestone 06.7C — Opt-in Kafka listener and typed local projection

**Scope:** a disabled-by-default Spring Kafka listener in the same Java/PostgreSQL application. This is a simulated read projection and **not** payment settlement or a deployment-tested authenticated Kafka consumer.

## What problem are we solving?

Milestones 06.7A–B demonstrated validation, inbox idempotency and manual Kafka offset handling. Real application wiring requires a listener container that manages poll lifecycle, consumer rebalance and application shutdown. The **business invariant** remains: only acknowledge a Kafka record *after* its related local database transaction commits. An invalid record must not vanish because a framework error handler logged the exception and advanced an offset.

## What we implemented

- `OptInKafkaPaymentListener` provides a Spring `@KafkaListener` registered only when `PAYMENT_KAFKA_CONSUMER_ENABLED=true`. Its topic is configured, and it requires an explicit stable `PAYMENT_KAFKA_CONSUMER_GROUP_ID`.
- `KafkaPaymentConsumerConfiguration` installs `MANUAL_IMMEDIATE` acknowledgments, synchronous offset commits, concurrency 1, and `CommonContainerStoppingErrorHandler`. A processing error stops the container; it does **not** silently recover or commit an invalid record.
- Activation fails closed unless `KAFKA_BOOTSTRAP_SERVERS` is explicitly configured, the consumer's effective `security.protocol` is `SSL` or `SASL_SSL`, and offset auto-commit is disabled. Actual broker identity, trust roots and topic ACLs must be configured and verified at deployment.
- `LocalKafkaPaymentRecordProcessor.processWithEvent(..., Consumer<PaymentOutboxEvent>)` adds a **typed effect** while preserving the existing Runnable learning interface; validation logic is not duplicated.
- Flyway V7 introduces `payment_event_projection` with ownership FKs. The typed `LocalPaymentEventProjection` inserts a simulated observation in the **same transaction** as the `payment_inbox` receipt. No money movement or real ledger effect occurs in this listener.
- An explicit integration test reads from a **real Kafka Testcontainers broker**, invokes the listener, checks persisted projection, verifies acknowledgment, deduplicates replay and rejects an invalid event without acknowledgment.

## Runtime sequence

```mermaid
sequenceDiagram
  autonumber
  participant K as Kafka broker
  participant L as Opt-in Spring listener
  participant V as Contract validator
  participant C as TrustedPaymentEventConsumer
  participant DB as PostgreSQL inbox and projection
  K-->>L: ConsumerRecord(topic,key,JSON)
  L->>V: processWithEvent(record, typed projection)
  V->>V: validate JSON, v1, key, state
  V->>C: consume(event, projection::record)
  C->>DB: BEGIN; validate existing outbox; register inbox
  alt First valid receipt
    C->>DB: insert projection observation
    C->>DB: COMMIT
  else Already processed
    C->>DB: no new projection
  end
  C-->>L: transaction returned
  L->>K: acknowledge() via MANUAL_IMMEDIATE
  Note over L,K: On exception, stop container; do not ACK failing record
```

## Recovery and limitations

A crash after DB commit but before Kafka offset commit can redeliver the same event; the unique inbox prevents another local DB effect. This is **at-least-once** with transactional local idempotency, not exactly-once delivery. A malformed message or unavailable database stops the listener. **No automated DLQ, quarantine administration or restart supervisor has yet been implemented**; operators must investigate and restart only after corrective action. Kafka topic/group ownership and ACLs must be validated in a properly secured target environment.

The listener's **integration test directly invokes the method using a real Kafka record**; it does not verify automatic Spring listener container startup against TLS/SASL. The Spring container wiring, startup/stop behavior and authenticated broker smoke tests need separate coverage before a production deployment claim.

**No duplicate class extraction:** event validation remains in `LocalKafkaPaymentRecordProcessor`, inbox atomicity remains in `TrustedPaymentEventConsumer`, offset handling belongs to the Kafka listener/container, and the typed projection owns the local read-model insertion. All are inside the existing modular monolith.

## Interview rehearsal

1. Why does `MANUAL_IMMEDIATE` make sense here, and what is its actual offset commit behavior?
2. What would happen if database processing succeeds but the consumer loses connectivity before the offset is acknowledged?
3. Why is a Kafka record key **not** a cryptographic authentication mechanism?
4. Why use `CommonContainerStoppingErrorHandler` rather than a default error handler that may eventually consider a message recovered?
5. Why must the projection and inbox receipt use the same PostgreSQL transaction?
6. What must change for an independently deployed consuming service that cannot query the producer's local outbox?
7. What evidence would you need before enabling the listener in production?

## Implementation references

- `src/main/java/dev/asambataiden/payment/payments/outbox/OptInKafkaPaymentListener.java`
- `KafkaPaymentConsumerConfiguration.java`, `LocalPaymentEventProjection.java`
- `LocalKafkaPaymentRecordProcessor.java`, `TrustedPaymentEventConsumer.java`
- `src/main/resources/db/migration/V7__local_payment_event_projection.sql`
- `src/test/java/dev/asambataiden/payment/payments/outbox/OptInKafkaPaymentListenerIT.java`
- [Spring Kafka manual acknowledgment](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- [Spring Kafka container stopping handler](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)

**Verification:** Record the final SHA and Java/Security CI after they complete; passing historical tests must not be treated as proof of this incremental change.
