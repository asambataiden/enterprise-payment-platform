# Chapter 24 — Kafka Commit-Acknowledgment Failure and Idempotent Recovery

**Milestone:** 06.7Q — post-transaction acknowledgment-failure regression.
**Status:** GitHub implementation committed; local Maven verification pending.

## The failure window

The opt-in payment listener applies a local effect and inbox receipt within
one PostgreSQL transaction and only then acknowledges the Kafka offset.
Kafka's offset commit is independent of PostgreSQL. A crash or acknowledgment
failure between these two operations may cause the same event to be delivered
again to the consumer group.

**What is guaranteed?** Local projection deduplication by
`(consumer_name, merchant_id, event_id)` for this simulated same-database
consumer. **What is not guaranteed?** Cross-system exactly-once delivery or
success of arbitrary nontransactional external side effects.

## Sequence diagram

```mermaid
sequenceDiagram
  participant K as Kafka
  participant L as OptInKafkaPaymentListener
  participant DB as PostgreSQL
  K->>L: Deliver event at offset N
  L->>DB: Verify local outbox and insert inbox + projection
  DB-->>L: COMMIT successful
  L--xK: ACK fails (simulated)
  Note over K,L: Committed DB effect, uncommitted Kafka offset
  K->>L: Redeliver event at offset N
  L->>DB: Check unique inbox receipt
  DB-->>L: Already consumed; no second projection
  L->>K: ACK
```

## What was added

`OptInKafkaPaymentListenerIT.failedAcknowledgmentAfterDatabaseCommitReplaysWithoutDuplicateEffect`
uses a PostgreSQL Testcontainer, creates a merchant-owned simulated payment,
records its state transition and outbox event, and delivers a valid Kafka-style
record to the listener.

The first mocked acknowledgment throws **after** the listener's successful DB
transaction. We assert the inbox and projection records exist exactly once.
A retry with a working acknowledgment must complete, report replay, and leave
the projection count unchanged.

This is a deliberate **in-process failure-injection test**, not a real broker
disconnect. Earlier `KafkaListenerContainerLifecycleIT` tests already use
a real Kafka container to verify poison fail-stop and replay of uncommitted
offsets. A full broker-level ambiguous-commit drill remains a future step.

## Why this matters

A distributed commit cannot atomically cover PostgreSQL and the Kafka consumer
group offset with this design. At-least-once delivery and transactionally
deduplicated database effects are a pragmatic and testable alternative.

The transport still needs authenticated broker configuration, credentials,
stable consumer-group ownership, controlled restart, alerting and operational
playbooks. Do not automatically retry poison events forever or claim that a
successful local test verifies production broker security.

## Verification

```bash
mvn -B --no-transfer-progress -Dit.test=OptInKafkaPaymentListenerIT verify
mvn -B --no-transfer-progress clean verify
```

If all previous tests remain unchanged, expect **138 tests** total:
79 unit and 59 integration. Record actual results before claiming success.

## Interview questions

**What if PostgreSQL commits but Kafka ACK fails?**
The offset can be replayed. Inbox uniqueness and same-transaction local effects
ensure the previously committed projection is not applied twice.

**Would skipping the message avoid duplicates?**
It would risk losing unprocessed data and break auditability; skipping is not
acceptable without an explicit, reviewed operator recovery decision.

**Does Kafka's idempotent producer solve this?**
No. Producer idempotence addresses selected producer-to-broker retry scenarios;
it does not atomically couple consumer offsets to a separate PostgreSQL commit.

**What is the test's limitation?**
It injects an acknowledgment exception rather than controlling a network
partition and broker group rebalance. A live broker fault-injection test remains
a separate integration milestone.
