# Milestone 06.7M — Canonical Kafka UUID Validation

## Problem and objective

Java `UUID.fromString()` has historically accepted abbreviated UUID groups,
although the Kafka event wire contract should use the standard 36-character
8-4-4-4-12 hexadecimal representation. A permissive parser creates ambiguity
between producers, validators and other consumers.

## Decision

At the Kafka consumer boundary, validate `eventId` and `paymentId` with
a case-insensitive canonical-format pattern before calling `UUID.fromString()`.

Uppercase hexadecimal text is accepted. Parsed UUID values are represented
internally by `java.util.UUID` and retain existing semantic identity.

Do not change the Kafka partition key, database UUID types or v1 JSON fields.

## Validation sequence

1. Expected topic.
2. Nonempty UTF-8 payload of at most 4096 bytes.
3. Strict JSON parsing (duplicate fields rejected).
4. Exactly the five allowed contract fields, v1 schema version.
5. Merchant and target-state validation.
6. Canonical event/payment UUID validation.
7. Exact Kafka key / merchant ownership check.
8. Trusted local-outbox and transactional inbox boundary.

This validation does **not** authenticate the producing service; production
systems also require broker authentication, topic ACLs, and service authorization.

## Regression evidence and limitations

New tests assert rejection of noncanonical `eventId` and `paymentId`
without calling the trusted consumer. An uppercase canonical event UUID
remains accepted and is passed to the trusted consumer unchanged semantically.

This chapter documents implementation, not a successful CI run. Verify locally
with:

```bash
mvn -B --no-transfer-progress -Dtest=KafkaConsumerContractTest test
mvn -B --no-transfer-progress clean verify
```

Expected test count if all prior tests remain unchanged: 129 total
(71 unit + 58 integration). Do not treat counts as verified until the command
exits successfully.

## Why not normalize merchant IDs at the same time?

Merchant IDs are tenant-scoping identity values in JWT claims, relational keys,
outbox/inbox records and Kafka keys. Unicode normalization, case-folding and
trimming can alter ownership semantics. Their length policy requires a separate,
explicit compatibility decision and migration assessment.

## Interview preparation

**Q: Why validate textual UUID format when Java already has a parser?**
A: Wire contracts must be deterministic across languages and implementations;
a permissive parser does not establish a canonical textual representation.

**Q: Is a well-formed UUID trustworthy?**
A: No. UUID format validation checks syntax, not source identity, authorization,
or that an event belongs to a particular merchant.

**Q: Why keep uppercase input valid?**
A: Uppercase hex represents the same UUID value and matches the documented
case-insensitive wire-format policy; UUID objects normalize the representation.
