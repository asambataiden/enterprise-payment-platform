# Milestone 06.7J — Strict Kafka JSON Parsing

## Objective

Reject ambiguous and malformed Kafka event payloads before
they reach the trusted transactional consumer.

## Implementation

Enabled Jackson StreamReadFeature.STRICT_DUPLICATE_DETECTION
on LocalKafkaPaymentRecordProcessor.

Added regression coverage for:
- Duplicate merchantId properties
- Null merchant identifiers
- Incorrect schemaVersion types
- Oversized multibyte UTF-8 payloads

## Why strict duplicate detection?

A JSON object with repeated property names can be interpreted
differently by permissive parsers.

Payment ownership must never depend on an ambiguous JSON field.

A duplicate with identical values is also rejected because
duplicate-property rejection is a syntax-level invariant.

## Trust boundary

Kafka record
  -> Topic validation
  -> UTF-8 payload-size validation
  -> Strict JSON parsing
  -> Event field, version, UUID and key validation
  -> Trusted outbox/inbox processing

Invalid records must not invoke the trusted consumer.

## Architectural trade-offs

The JSON Schema remains a reviewed contract artifact.
Runtime validation is still implemented separately.

The production validator does not yet implement the entire
JSON Schema Draft 2020-12 specification.

Jackson is currently resolved transitively. A direct
dependency declaration should be reviewed separately.

Merchant identifier Unicode length semantics also need
a cross-layer compatibility review.

## Verification

2026-11-09:
- 65 unit tests passed
- 58 integration tests passed
- 123 total tests passed
- Zero failures, errors or skipped tests

Repeat verification after any final test modification.

## Interview preparation

Q: Why reject duplicate JSON fields?
A: They create ambiguous parsing behavior and can undermine
validation assumptions between independent components.

Q: Why count UTF-8 bytes instead of Java characters?
A: Kafka payload limits concern encoded message size.

Q: Does strict JSON parsing authenticate Kafka producers?
A: No. Authentication, authorization, tenant ownership
and transactional outbox verification remain separate.

## Next increment

Review Jackson dependency ownership and cross-layer
identifier/contract compatibility before schema evolution.
