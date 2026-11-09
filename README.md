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
| `GET` | `/actuator/health` | Public |
| Other routes | — | Denied by default |

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

**Last feature baseline** (commit `98ace4d`, milestone 06.6): [Java CI #179](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42556896756) **89/89 tests** (46 fast + 43 PostgreSQL/Kafka integration); [Security CI #93](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42556896796) passed. Documentation-only commits after this baseline require a fresh CI check on the latest SHA. Tests include a real **test Kafka broker** and PostgreSQL but do not validate a deployed identity provider, production TLS/SASL broker, or financial settlement. Trivy and Gitleaks are useful but are not a complete software-supply-chain or security assessment.

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
