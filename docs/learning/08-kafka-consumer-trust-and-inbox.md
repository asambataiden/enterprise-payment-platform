# Milestone 06.7A — Validate Kafka messages before local transactional consumption

**Status:** Internal record processor and real-broker integration tests **verified** at commit `6c1b98e` (Java CI #225 and Security CI #139). NOT an automatically running listener, authenticated remote consumer, or production deployment.

## Problem — why publishing events is not enough

Kafka records are external input. A valid JSON document is **not proof** that its sender was authorized. A successful broker read is **not proof** that the claimed merchant/payment belongs to the caller. Re-delivery after a crash is normal: an event must not execute the same local business action twice.

A safe consumer must distinguish (1) wire contract validation, (2) **transport trust** based on authenticated broker identities/topic ACLs, (3) domain authorization and ownership, (4) transactional deduplication and effects, (5) **offset acknowledgment** and controlled recovery. These are different boundaries.

## What was implemented

- `LocalKafkaPaymentRecordProcessor` accepts an explicit Kafka `ConsumerRecord<String,String>` and a local DB effect. This is **not** a Spring `@KafkaListener`, scheduler or running consumer group.
- It checks a fixed expected topic, non-empty bounded UTF-8 payload (maximum 4 KiB), exact v1 JSON fields, supported version, merchant ID constraints, accepted simulated state names, UUIDs and **Kafka key equals `merchantId:paymentId`**.
- The existing `TrustedPaymentEventConsumer` then verifies the event against the *local* committed `payment_outbox` (matching merchant, event, payment and state) and inserts a unique `payment_inbox` receipt in the **same PostgreSQL transaction** as the supplied local DB effect.
- Duplicate delivery skips the local effect. If the local effect fails, the receipt rolls back, so a retry is possible.
- The new `LocalKafkaPaymentRecordProcessorIT` uses a **real ephemeral Kafka broker**, a real PostgreSQL database, committed simulation state, and negative tests for forged events, mismatched keys, wrong topics, unknown schema versions, extra JSON fields and invalid/oversized JSON.

## Runtime sequence — as built, **manual invocation**

```mermaid
sequenceDiagram
  autonumber
  participant K as Kafka broker
  participant C as Test / explicit Kafka consumer
  participant V as LocalKafkaPaymentRecordProcessor
  participant DB as PostgreSQL
  participant I as TrustedPaymentEventConsumer
  K-->>C: ConsumerRecord(topic,key,JSON)
  C->>V: process(consumerName,record,effect)
  V->>V: Check topic, JSON fields/version, key, size
  alt Invalid record
    V--xC: Reject (no inbox write)
  else Structurally valid
    V->>I: consume(event,effect)
    I->>DB: BEGIN; verify matching committed outbox
    I->>DB: INSERT inbox receipt ON CONFLICT DO NOTHING
    alt Already processed
      I-->>C: false; skip effect
    else New event
      I->>DB: Local DB effect (same transaction)
      I->>DB: COMMIT
      I-->>C: true
    end
  end
```

## What we **must not claim**

- An incoming Kafka record is **not cryptographically authenticated by checking a string key**. For remote services, require provisioned SASL_SSL/mTLS identities, restricted topics/ACLs, secure secret injection, and ideally a producer/consumer ownership model with reviewed trust assumptions.
- Matching against the **producer's local outbox** only works for this same-database exercise. A remote consumer must own its own inbox and cannot rely on access to a producer's private database.
- There is **no offset commit logic** yet. A future listener must acknowledge after database commit and distinguish retryable processing failures from malformed/poison events; otherwise data loss or infinite replay can result.
- JSON field checking is an **exact v1 contract**, not a deployed registry/schema compatibility program. Version rollouts, compatibility tests and schema registry remain planned.
- A `Runnable` local effect is useful as an isolated demonstration; an enterprise adapter should use a **typed application port/use case** rather than arbitrary callbacks. External side effects are not made transactional by the inbox.

## Why this order is appropriate

We first prove **validation + transactional effect + duplicate behavior** in a small, deterministic component. Only then should we add a production listener lifecycle and offset handling: otherwise a failing consumer can commit offsets prematurely or perform unauthorized operations while returning success.

An authenticated Kafka transport is a *separate deployment control*. The project currently demonstrates encrypted producer configuration guards but does not have an environment with verified broker credentials and ACLs.

## Practice tasks and interview checkpoints

1. Explain the difference between a Kafka record key, JWT merchant identity, topic ACL, outbox event ID and inbox receipt. Which authenticates an entity?
2. Change `schemaVersion` from 1 to 2: why should this consumer reject it rather than silently guess?
3. Change the merchant in JSON but not the record key: where is the mismatch detected?
4. Send a structurally correct forged event with a random UUID: why does the local outbox validation reject it? Why is that **not** sufficient for a remote service?
5. Deliver the same record twice: explain `ON CONFLICT DO NOTHING` and why local DB effects must be in the receipt transaction.
6. Crash after DB commit but before Kafka offset acknowledgment: predict redelivery and deduplication.
7. Describe a **safe** future poison-message policy: quarantine, controlled replay, audit trail and offset handling, avoiding infinite retries.

## Sources and code links

- `src/main/java/dev/asambataiden/payment/payments/outbox/LocalKafkaPaymentRecordProcessor.java`
- `TrustedPaymentEventConsumer.java`, `PaymentInbox.java`, `PaymentOutboxEvent.java`
- `src/test/java/dev/asambataiden/payment/payments/outbox/LocalKafkaPaymentRecordProcessorIT.java`
- [Prior Kafka producer contract](../architecture/kafka-integration-contract.md)
- [End-to-end runtime diagrams](../architecture/runtime-scenarios.md)
- [Transactional outbox discussion](https://microservices.io/patterns/data/transactional-outbox)
- [Idempotent consumer discussion](https://microservices.io/patterns/communication-style/idempotent-consumer.html)

**Next acceptance gate:** add opt-in authenticated Kafka listener with explicit offset/DLQ policy, typed local effect, validated topic ACL settings and replay tests. Do not auto-enable consumption for a demonstration.

## Verified evidence (implementation baseline)

- [Java CI #225](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42562622250): **94/94 tests passed** — 48 fast and 46 integration, including 3 new Kafka/PostgreSQL consumer tests.
- [Security CI #139](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42562622169): Trivy and Gitleaks passed.
- Evidence is anchored to implementation commit `6c1b98e`. A later documentation-only commit needs its own current-head checks before being called verified.
