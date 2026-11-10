# Enterprise Payment Platform

> **Educational simulated payment-intake service** built with Java 25, Spring Boot 4.1.2, PostgreSQL 17, Flyway and Maven. It does **not** move funds or integrate with a regulated payment processor and is **not production ready**.

## Start here — learn the entire capstone

**New:** [Guided labs from scratch](docs/learning/guided-labs.md) · [Learning and Interview Handbook](docs/learning/README.md) · [Full roadmap from inception](docs/roadmap/enterprise-payment-platform.md) · [Bird's-eye architecture and module views](docs/architecture/system-overview.md) · [Sequence diagrams and use cases](docs/architecture/runtime-scenarios.md) · [Modularity, DRY and reusable library policy](docs/architecture/modularity-and-reuse.md) · [Senior engineer interview workbook](docs/learning/interview-workbook.md).

Follow the roadmap chronologically; every milestone explains **what**, **why**, **where it fits**, **how**, tradeoffs, tests, failures and interview arguments. Diagrams distinguish running functionality from unimplemented target components. Documentation changes travel with code in the same PR.

## Verified capabilities

- Hexagonal application/persistence boundary with a PostgreSQL-backed payment store.
- Merchant-scoped, concurrent-safe idempotency using a composite unique index and atomic `INSERT … ON CONFLICT`.
- Stateless OAuth2 Resource Server using JWT authentication and `payments:read` / `payments:write` scopes.
- Merchant ownership derived from the **validated** JWT `merchant_id` claim, never from the request body or a user-controlled header.
- RFC 9457 Problem Details, validation, generated correlation IDs, ECS logging and a health endpoint.
- PostgreSQL Testcontainers, Flyway V1→V2 upgrade tests, signed-token HTTP integration tests and Java 25 GitHub Actions CI.

## Toolchain

Java 25, Maven 3.9+, PostgreSQL 17, Spring Boot 4.1.2, Docker Compose, Testcontainers 2.x, JUnit 5 and ArchUnit.

## Local development

Set a **local-only** database password explicitly; no database password is stored in tracked source code or silently defaulted.

```bash
export DB_PASSWORD='replace-with-a-unique-local-development-password'
docker compose up -d postgres
mvn -B clean verify
```

The `DB_PASSWORD` environment variable must be present when starting Docker Compose or the application. The literal above is a placeholder, **not** a recommended password.

For application startup, configure an approved identity provider that issues signed access tokens with `merchant_id`, correctly scoped `scope` values and the expected audience:

```bash
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI='https://your-idp.example/realms/payments'
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES='enterprise-payment-service'
mvn spring-boot:run
```

The issuer above is an example; configure the real issuer, audience and token-issuance policy before starting a real service. The JWT integration tests use ephemeral signing keys and a test-only decoder, **not** a production identity provider.

## API authorization

| Method | Path | Access |
| --- | --- | --- |
| `POST` | `/api/v1/payments` | Valid JWT, `payments:write`, trusted merchant claim, `Idempotency-Key` |
| `GET` | `/api/v1/payments/{id}` | Valid JWT, `payments:read`, owning merchant |
| `GET` | `/api/v1/payments/{id}/history` | Valid JWT, `payments:read`, owning merchant; up to 100 newest committed simulation transitions |
| `POST` | `/api/v1/payments/{id}/simulated-transitions` | Valid JWT, **`payments:simulate`**, owning merchant, `Event-Id` UUID |
| `GET` | `/actuator/health` | Public |
| Other routes | — | Denied by default |

The history endpoint returns the current merchant-owned payment and a
bounded list of committed simulated lifecycle events, ordered newest-first.
Each event includes a UUID, target state, recording time, optional outbox
dispatch time, and optional linked ledger journal ID (capture/refund).
`hasMore=true` means the latest 100 events omit older history; this
increment does **not** implement pagination of older entries. Dispatch
timestamps do not guarantee downstream processing. These records do not
establish a legally complete audit trail or real financial settlement.

The simulation-only endpoint accepts a validated JSON body such as
`{"targetState":"AUTHORIZED"}`, a merchant-owned payment UUID and an
`Event-Id` UUID. Reuse the **same event UUID** for retries of the same
logical state change; reusing it for another payment or target state is
rejected. The dedicated `payments:simulate` permission is intentionally
separate from ordinary `payments:write`. Invalid transitions return
HTTP 409 with `PAYMENT_TRANSITION_CONFLICT`; other merchants' payment
IDs return 404. This route exists for an explicitly authorized training
environment only. It does **not** integrate a payment processor or cause
external authorization, capture, refund or settlement.

Example of a request **after** obtaining a legitimate access token from the configured identity provider:

```bash
curl -i http://localhost:8080/api/v1/payments \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-order-001' \
  -d '{"amount":12.50,"currency":"EUR","merchantReference":"order-001"}'
```

`RECEIVED` indicates a simulated intake record, not card authorization or settlement.

## Verification and security gates

```bash
mvn -B clean verify
```

**Current verified baseline (November 10, 2026, commit `838b434`):**
[Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42755389917)
and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42755390083)
both passed on `feat/payment-ledger`. Java CI executes Maven `clean verify`
with PostgreSQL/Kafka Testcontainers and the application, security and
architecture regression tests. The locally verified
`SimulatedPaymentLifecycleAcceptanceIT` covers the simulated
Create → Authorize → Capture → Refund → Ledger reversal → Outbox →
Inbox → Local projection workflow, including replay safety.
This acceptance test uses an in-process publisher rather than a Kafka
network hop; no real funds movement or external payment-provider
integration is implemented. See
[execution evidence](docs/learning/35-execution-evidence-and-retrospective-runbook.md).
Trivy and Gitleaks do not constitute a complete security audit.

## Further hardening required

- Choose and validate a production identity provider's token issuer, audience, merchant claim mapping and rotation policy.
- Use workload identity and managed secret injection; avoid static credentials in deployment manifests, command history or logs.
- Enforce tenant isolation with least-privileged DB accounts and optionally PostgreSQL row-level security as defense-in-depth.
- Assess OWASP ASVS controls, rate limiting, auditability, error redaction, HTTP/TLS deployment and dependency security results.
- Resolve outstanding findings in [PR #1 release readiness](docs/reviews/pr-0001-release-readiness.md) before treating this as production capable.

## Architecture quality and public API contract (06.7F)

The Kafka adapters now live under `payments.messaging.kafka.producer` and `payments.messaging.kafka.consumer`, while `payments.outbox` retains broker-independent dispatch and inbox semantics. [Messaging package boundaries](docs/architecture/messaging-package-boundaries.md) explain ownership and ArchUnit rules.

Public errors use RFC 9457 `application/problem+json` with a stable `code` field (e.g. `PAYMENT_IDEMPOTENCY_CONFLICT` or `PAYMENT_NOT_FOUND`). Domain exceptions remain independent of HTTP status and OpenAPI annotations.

OpenAPI v1 is **disabled by default** and has no Swagger UI dependency. To expose JSON documentation in a properly authenticated local environment, set `PAYMENT_OPENAPI_ENABLED=true`; `GET /v3/api-docs` then requires the dedicated JWT authority `SCOPE_payments:docs`. See [Learning Chapter 13](docs/learning/13-package-refactor-error-contracts-openapi.md). Never expose this endpoint anonymously on an internet-facing deployment.

## Engineering documentation

The [learning handbook](docs/learning/README.md) is the documentation entry point. It links all milestone and scenario records without duplicating them.

- [Complete incremental roadmap](docs/roadmap/enterprise-payment-platform.md)
- [System context, container, component and data diagrams](docs/architecture/system-overview.md)
- [Runtime use cases and sequence diagrams](docs/architecture/runtime-scenarios.md)
- [Modules, reuse and future library extraction](docs/architecture/modularity-and-reuse.md)
- [Interview workbook](docs/learning/interview-workbook.md)
- [Outbox delivery contract](docs/architecture/outbox-delivery-contract.md)
- [Kafka transport contract](docs/architecture/kafka-integration-contract.md)

- [Merchant isolation and Flyway upgrade](docs/security/merchant-isolation.md)
- [JWT configuration and trust boundary](docs/security/jwt-resource-server.md)
- [PR #1 review findings](docs/reviews/pr-0001-release-readiness.md)
- [ADR-0001 — Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR-0002 — Money and idempotency](docs/adr/0002-money-and-idempotency.md)

## License

MIT. Educational implementation; no real funds movement or confidential employer code.
