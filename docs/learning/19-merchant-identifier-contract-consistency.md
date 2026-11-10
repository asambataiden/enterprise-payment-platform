# Milestone 06.7L — Merchant Identifier Contract Consistency

## Objective

Ensure the payment event model does not accept merchant identifiers
that the Kafka consumer rejects.

## Finding

The HTTP, application, ledger and Kafka consumer layers already
enforce a maximum merchant identifier length of 128 Java UTF-16
code units.

PaymentOutboxEvent previously checked only for a nonblank value.

That created inconsistent construction and consumption rules.

## Decision

Add the same 128-code-unit boundary to PaymentOutboxEvent.

Do not silently change existing persisted identifier semantics.

## Implementation

PaymentOutboxEvent rejects:
- Null merchant identifiers
- Empty merchant identifiers
- Blank merchant identifiers
- Merchant identifiers exceeding 128 Java code units

## Validation boundaries

Authentication and merchant authorization remain responsibilities
of the trusted API and application layers.

Event construction enforces local object invariants.

Kafka parsing enforces wire-format invariants.

The trusted consumer independently verifies the event against
the committed local outbox.

## Compatibility considerations

Java String.length() counts UTF-16 code units.

JSON Schema maxLength counts Unicode code points.

These measures differ for supplementary Unicode characters.

The currently published v1 schema therefore does not completely
describe the application's existing identifier-length policy.

A later compatibility decision must reconcile these rules across
HTTP, persistence, events and consumers.

No schema evolution or persisted identifier migration is
performed in this milestone.

## Test strategy

Focused tests cover:
- 128-character acceptance
- 129-character rejection
- Null, empty and blank identifiers
- Unsupported schema versions

## Interview preparation

Q: Why validate an identifier in a domain/event record?

A: Records can be instantiated independently of an HTTP request.
Their invariants must not depend on controller validation.

Q: Why not change every validator immediately?

A: Identifier formats are compatibility-sensitive, especially
when they appear in database keys and Kafka partition keys.

Q: Is this sufficient to guarantee event authorization?

A: No. Event data validity and source authorization are
independent security properties.

## Follow-up

Define a canonical merchant identifier policy, review Unicode
semantics and enforce schema/runtime compatibility through tests.
