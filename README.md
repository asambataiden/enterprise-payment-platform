# Enterprise Payment Platform

> **Educational simulated payment-intake service** built with Java 25, Spring Boot 4.1.2, PostgreSQL 17, Flyway and Maven. It does **not** move funds or integrate with a regulated payment processor and is **not production ready**.

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

Milestone 05.4 was verified locally with **51/51 tests passing** (35 Surefire tests and 16 Failsafe integration tests) and in GitHub CI run #83. **Milestone 05.5 configuration and security-scanning changes must be independently verified.** New `Security CI` scans checked-out source and discovered dependencies using Trivy; it does not replace historical Git secret scanning, a full SBOM, CodeQL or security review.

## Further hardening required

- Choose and validate a production identity provider's token issuer, audience, merchant claim mapping and rotation policy.
- Use workload identity and managed secret injection; avoid static credentials in deployment manifests, command history or logs.
- Enforce tenant isolation with least-privileged DB accounts and optionally PostgreSQL row-level security as defense-in-depth.
- Assess OWASP ASVS controls, rate limiting, auditability, error redaction, HTTP/TLS deployment and dependency security results.
- Resolve outstanding findings in [PR #1 release readiness](docs/reviews/pr-0001-release-readiness.md) before treating this as production capable.

## Engineering documentation

- [Merchant isolation and Flyway upgrade](docs/security/merchant-isolation.md)
- [JWT configuration and trust boundary](docs/security/jwt-resource-server.md)
- [PR #1 review findings](docs/reviews/pr-0001-release-readiness.md)
- [ADR-0001 — Modular monolith](docs/adr/0001-modular-monolith.md)
- [ADR-0002 — Money and idempotency](docs/adr/0002-money-and-idempotency.md)

## License

MIT. Educational implementation; no real funds movement or confidential employer code.
