# Chapter 25 — Kafka Consumer Recovery Assessment and Operator Triage

**Milestone:** 06.7R — read-only operational recovery evidence.
**Status:** Code and tests committed; local Maven and CI verification pending.

## Business and operational problem

Our opt-in consumer stops on invalid, untrusted or unprocessable records.
The failed record is **not** acknowledged. That protects against silent loss,
but the consumer group may accumulate lag until an authorized operator
investigates the actual cause and approves a safe remediation.

The existing V8 `kafka_consumer_failure_observations` table stores
(consumer name, topic, partition, offset), failure category, SHA-256 fingerprint,
occurrence count and first/last observed timestamps. It is forensic evidence,
**not** a dead-letter queue, proof of producer identity or authorization to skip.

## This increment

`KafkaConsumerRecoveryAssessment` provides a tightly scoped, read-only lookup
using the **complete** `(consumerName, topic, partition, offset)` location.
It returns a typed, immutable observation value with the existing metadata.
Unknown locations return `Optional.empty()`; an empty result **does not**
prove the event was successfully processed.

The class has no Kafka administrative client, REST endpoint, offset mutation,
consumer restart, permissions bypass, payload retrieval or replay command.
It is intentionally not exposed as an unprotected operational API.

`KafkaConsumerRecoveryAssessmentIT` verifies the observation count, exact
lookup scoping, absent entries, rejected invalid coordinates, metadata
timestamps and absence of raw payload/key in the returned value.

## Sequence diagram

```mermaid
sequenceDiagram
  participant K as Kafka broker
  participant L as Fail-stop listener
  participant DB as PostgreSQL
  participant O as Authorized operator
  participant R as RecoveryAssessment
  K->>L: Record topic=T partition=P offset=N
  L->>DB: Validation or processing fails
  L->>DB: REQUIRES_NEW observation (V8)
  L--xK: No ACK; listener stops
  O->>R: Investigate exact T/P/N and consumer
  R->>DB: SELECT immutable observation metadata
  DB-->>R: Category, digest, count, timestamps
  R-->>O: Evidence only; no recovery action
  Note over O,K: Approval and broker offset examination are separate controls
```

## Authorized operator recovery workflow (manual process)

1. **Detect:** alert on stopped listener, missing healthy processing and
   consumer-group lag. Determine affected group, topic, partition and offset.
2. **Locate:** retrieve exact V8 metadata using the read-only assessment
   within an authorized environment. Avoid exposing the endpoint publicly.
3. **Correlate:** inspect Kafka's current and committed offsets using
   separately authorized broker tooling and verify record retention.
   A V8 observation alone cannot establish broker commit position.
4. **Classify:** contract mismatch, unauthorized event, transient DB failure,
   infrastructure issue or unknown cause. Escalate suspicious producer activity.
5. **Fix:** remove the root cause via reviewed schema/producer changes,
   identity-policy correction, DB recovery or deployment remediation.
6. **Approve:** record ticket, incident owner, approver, SHA-256 fingerprint,
   exact location, proposed operation, rollback plan and impact.
7. **Recover:** only through an independently implemented, reviewed and
   access-controlled mechanism. Prefer restarting with the same stable
   group ID to retry an uncommitted event after correcting the cause.
8. **Verify:** confirm successful projection/inbox, expected offset commit,
   stable group health and no unexpected repeats or gaps. Close the incident.

**Do not** automatically use `seek`, `alterConsumerGroupOffsets`, DLQ or
manual commits to bypass poison records. This milestone **does not implement**
or authorize any of those operations.

## Threats and limitations

- Hashes of predictable payloads are not private, cryptographic
  authentication or proof of who published an event.
- A DB observation can survive listener failure, but absence of an observation
  is not evidence of successful handling.
- A stopped listener may cause an availability incident: lag, alerts,
  incident response SLAs and backlogs require production design.
- The same-database `TrustedPaymentEventConsumer` is not transferable to
  independently deployed services without explicit authenticated provenance.
- There is no live TLS/SASL smoke-test evidence, broker-ACL validation or
  production-grade operator authorization system here.
- The read-only lookup is an internal Java facility, not an externally
  exposed HTTP endpoint or an executable restart workflow.

## Evidence and commands

Read `KafkaConsumerFailureObservations`, `KafkaConsumerRecoveryAssessment`,
`OptInKafkaPaymentListener`, `KafkaListenerContainerLifecycleIT` and Flyway V8.

```bash
mvn -B --no-transfer-progress -Dit.test=KafkaConsumerRecoveryAssessmentIT verify
mvn -B --no-transfer-progress clean verify
```

If the previous 138-test baseline stays unchanged, expect **140 tests**
(79 unit, 61 integration). Do not claim verification before seeing Maven
output for the exact commit.

## Interview preparation

**Q: What does fail-stop accomplish?**
It prevents silently committing beyond an invalid or untrusted event. It also
requires monitoring and a deliberate recovery process to restore availability.

**Q: Why no automatic restart?**
A poison event would be retried indefinitely, obscuring the root cause while
generating repeated incidents. Root-cause classification and remediation
precede operator-approved restart.

**Q: Why not automatically skip the offset?**
That discards an event without proving its effects are unnecessary. Payment
event streams require explicit auditability and ownership.

**Q: Does this class authorize safe replay?**
No. It only reads existing metadata. Broker positions, change approval,
authorization and post-recovery validation are separate responsibilities.

**Q: How would you mature it for production?**
Introduce a secured operations interface, RBAC, approvals, immutable incident
audit records, broker-position verification, retention policy, alerts and
tested failover/restart procedures, without bypassing the trust boundary.
