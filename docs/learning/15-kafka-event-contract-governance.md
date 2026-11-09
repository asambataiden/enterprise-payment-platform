# Milestone 06.7H — Kafka Event Contract Governance

## Objective

Establish a reviewed version-1 JSON contract for simulated
payment transition events and automatically detect selected
producer/schema incompatibilities.

## Architecture

Payment transition
  -> PostgreSQL transactional outbox
  -> Kafka producer
  -> Kafka broker
  -> Kafka consumer validation
  -> Trusted local inbox and projection

This milestone adds test-only contract governance.
It does not introduce a production schema registry or
change Kafka consumer runtime validation.

## Why

Asynchronous consumers depend on stable event fields,
types, identifiers, and allowed values.

An unreviewed producer change can break consumers even
when the producer application compiles successfully.

## Implementation

- payment-event-v1.schema.json: reviewed JSON Schema
- PaymentEventContractTest: five contract tests
- Existing Kafka integration tests: real broker verification
- Existing Maven verify: automatic CI integration

## Compatibility policy

Version 1 requires exactly five attributes:

- schemaVersion
- eventId
- merchantId
- paymentId
- targetState

Unknown attributes and unsupported versions are rejected
by the existing strict local Kafka consumer.

Additive fields are therefore NOT automatically compatible
with the deployed v1 consumer.

Contract modifications require consumer impact analysis,
explicit review, and appropriate versioning.

## Security considerations

- JSON Schema validation is not authentication.
- A valid payload does not establish producer provenance.
- Merchant ownership must be independently verified.
- The Kafka record key must match the event owner/payment.
- Invalid events must not execute database effects.
- Broker authentication and ACLs remain deployment concerns.

## Limitations

The new tests check selected JSON Schema constraints.
They are not a complete Draft 2020-12 validator.

The tests do not yet prove every schema constraint matches
runtime validation. Future increments should strengthen
that alignment without duplicating business logic.

## Verification

Focused:
mvn -B -Dtest=PaymentEventContractTest test

Full:
mvn -B clean verify

Local evidence (2026-11-09):
57 unit tests and 58 integration tests passed.
Total: 115; failures: 0; errors: 0; skipped: 0.

## Interview preparation

1. Why version Kafka events independently of HTTP APIs?
2. What makes an event schema change backward compatible?
3. Why can an additive field break a strict consumer?
4. How does inbox deduplication differ from schema validation?
5. Why does valid JSON not prove trusted event provenance?
6. What benefits justify introducing a schema registry?

## Next

Evaluate maintained JSON Schema validation and strengthen
producer-consumer compatibility coverage.
