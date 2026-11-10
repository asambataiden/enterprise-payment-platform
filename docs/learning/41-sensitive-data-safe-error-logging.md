# Chapter 41 — Sensitive-data-safe API failure logging

## Risk identified in the previous milestone

The RFC 9457 error responses correctly redacted internal exception
details, but `PaymentApiExceptionHandler` still called SLF4J with the
original exception as its final argument. With structured ECS logging,
this serializes an exception's **message, stack trace and cause chain**.
Driver connection strings, sensitive request values, credentials or
infrastructure host names can therefore leak into application logs,
even when HTTP responses are safe.

This chapter is a focused defensive fix, **not** a logging framework
rewrite or a promise that every other log producer is safe.

## Decision and implementation

At the API exception boundary, write only allowlisted, low-cardinality
fields for recognized database-resource and unexpected failures:

- Fixed classification: `DATABASE_RESOURCE_UNAVAILABLE` or `UNEXPECTED`.
- Stable public code: `SERVICE_UNAVAILABLE` or `INTERNAL_ERROR`.
- Server-generated `correlationId` from the existing correlation filter.
- Log severity `ERROR`.

Do **not** log the raw Throwable object, message, nested cause,
stack trace, SQL, URL or request body in this path.
`correlationId` may be null in isolated unit tests that do not pass
through the HTTP filter. This does not require a new dependency.

## Why redacting strings after formatting is insufficient

A driver or library can put secrets in an exception's cause chain,
suppressed exceptions or stack-trace extensions. Simple string
replacement or regular expressions do not reliably cover every
secret type. Deny-by-default, schema-constrained logs are safer for
payment API failure boundaries.

## Observability trade-off

Suppressing all raw exceptions reduces on-call diagnosis of
individual failures. Operators retain the category, code, timestamp,
service metadata and request correlation identifier; other approved
telemetry can help triage an incident. More detailed diagnostic
capture—if genuinely needed—requires an explicitly reviewed,
access-controlled and retention-limited mechanism with data
minimization. Do not silently re-enable stack traces in production.

The API still returns a sanitized RFC 9457 500 or 503 response,
including the correlation identifier when available. The database
outage returns the existing advisory `retryable: true` hint;
client idempotency semantics remain unchanged.

## Test evidence and verification

A regression test attaches a temporary Logback `ListAppender` to
the API exception handler logger, emits failures with synthetic
hostnames, credentials, bearer-token strings and sensitive nested
causes, and asserts:

1. Exactly two intentional error events were emitted.
2. Messages match the fixed categories, codes and correlation ID.
3. Events carry no `ThrowableProxy` or raw exception details.
4. The logger appender and test MDC are restored in a `finally` block.

Run the focused suite:

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger
mvn -B --no-transfer-progress -f "$ROOT/pom.xml" \
  -Dtest=PaymentApiExceptionHandlerTest,PaymentControllerWebTest test
```

Full quality gate: `mvn -B --no-transfer-progress -f "$ROOT/pom.xml" clean verify`.
The milestone is **not verified** until local regression and Java +
Security CI pass.

## Interview questions

1. Why is a sanitized HTTP response insufficient to protect logs?
2. How does SLF4J treat a Throwable supplied as the last argument?
3. Why prefer an allowlist over message-string redaction?
4. Which categories and fields are useful without becoming sensitive?
5. How would you authorize detailed debugging and control retention?
6. How should log processing handle secrets emitted by third-party
   frameworks that bypass this exception boundary?
