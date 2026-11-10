# Milestone 06.7I — Kafka Consumer Contract Compatibility

## Objective

Verify that the existing Kafka consumer rejects malformed
and incompatible event messages before entering the trusted
transactional processing boundary.

## Previous baseline

Milestone 06.7H:
- 115 tests passed
- Versioned event v1 JSON Schema established
- Producer serialization contract tested
- Contract governance documented

## Implementation

Added KafkaConsumerContractTest with five tests.

The tests verify:

1. A valid v1 event reaches the trusted consumer.
2. An unsupported schema version is rejected.
3. Missing or unexpected attributes are rejected.
4. A mismatched merchant/payment record key is rejected.
5. Invalid event identifiers and unsupported states are rejected.

## Architecture decision

Keep wire-format validation in the Kafka consumer boundary.

The domain record remains transport-independent.

The trusted consumer remains responsible for verifying
the event against the local transactional outbox,
registering the inbox receipt and executing the local
database effect.

Mockito is used to isolate boundary validation.

Existing PostgreSQL/Kafka integration tests verify
transactional behavior and real broker integration.

## Security considerations

A structurally valid message is not necessarily trusted.

Kafka topic, record key and JSON structure validation
do not authenticate the original publisher.

Remote deployments require independently configured
broker authentication, authorization and ACLs.

The local outbox lookup is valid only for the
same-database simulation architecture.

## Known limitations

- JSON Schema and runtime validation are maintained separately.
- The current tests do not cover every schema constraint.
- Full Draft 2020-12 validation is not implemented.
- Event evolution requires a documented compatibility policy.
- Schema validity does not establish payment-state authorization.
- These tests do not prove remote Kafka trust guarantees.

## Verification

Local execution on 2026-11-09:

Focused KafkaConsumerContractTest:
5 tests passed.

Full Maven verification:
62 unit tests passed.
58 integration tests passed.
120 total tests passed.
0 failures.
0 errors.
0 skipped.

## Interview preparation

### Why validate before invoking the transactional consumer?

To reject invalid wire data before it can reach the
database processing boundary.

### Why use mocks in these tests?

To prove whether the trusted-consumer boundary is called
without needing a database or Kafka broker.

### Why retain integration tests?

Mocks cannot prove real transaction boundaries,
outbox ownership verification or inbox deduplication.

### Is valid JSON sufficient for event security?

No. Authentication, authorization, tenant ownership
and business-state validation are separate controls.

## Next increment

Evaluate a maintained JSON Schema validator and
strengthen schema/runtime consistency tests.

Do not silently relax strict v1 consumer compatibility.
