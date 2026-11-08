# ADR-0002: Represent money as decimal and require idempotency keys

- Status: Accepted for Milestone 1, with known limitations
- Date: 2026-11-08

## Decision
- Use Java `BigDecimal`, PostgreSQL `NUMERIC(19,2)`, and ISO-style three-letter uppercase currency codes.
- Require an `Idempotency-Key` header for every POST request.
- Repeated sequential requests with the same key and semantically equal payload return the stored representation.
- Reuse with a different payload returns HTTP 409.

## Limitations
- This is an **educational simulated payment intake**, not money movement.
- A two-decimal scale is not valid for all currencies. A production design must implement ISO 4217 currency-specific minor units or represent money in integral minor units with explicit currency rules.
- Idempotency is **not concurrency-safe yet**: simultaneous requests can hit the DB uniqueness constraint and result in an unhandled error. Milestone 2 adds concurrency tests and deterministic conflict/replay handling.
- Keys are currently global. Production designs would scope keys by tenant/client and authenticated principal.
- No authorization, authentication, rate limits, fraud controls, PCI scope assessment, or ledger is implemented. Do not expose this API publicly.
