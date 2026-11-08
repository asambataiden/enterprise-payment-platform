# Enterprise Payment Platform

> **Status: Milestone 1 scaffold.** Educational simulated payment-intake service for an enterprise Java engineering portfolio. **No real money movement, no payment provider integration, and not suitable for internet-facing production deployment.**

## Purpose
Demonstrate domain modeling, REST API design, transaction boundaries, PostgreSQL schema migrations, validation, idempotency fundamentals, and testable Spring Boot service design. Later milestones add identity, concurrency correctness, ledger principles, event-driven messaging, CI/CD, Kubernetes and observability.

## Stack
- Java **25 LTS** (project target)
- Spring Boot **4.1.2**
- Maven 3.9.x, PostgreSQL 17, Flyway, Spring Data JPA, JUnit/Mockito
- Docker Compose for local PostgreSQL

## Getting started
Prerequisites: JDK 25, Maven 3.9+, Docker Compose.

```bash
# The password below is a local demo default; do not use outside isolated development.
docker compose up -d postgres
mvn -B clean verify
mvn spring-boot:run
```

Create a simulated payment record:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: example-order-001' \
  -d '{"amount":12.50,"currency":"EUR","merchantReference":"order-001"}'
```

Check readiness: `curl http://localhost:8080/actuator/health`.

### API contract
| Method | Route | Description |
| --- | --- | --- |
| `POST` | `/api/v1/payments` | Store a **simulated** payment intake request, requires `Idempotency-Key` |
| `GET` | `/api/v1/payments/{id}` | Retrieve intake status |
| `GET` | `/actuator/health` | Basic health probe |

Response `status: RECEIVED` does **not** represent settlement or even authorization.

## Security and correctness warnings
- No authentication/authorization; do not deploy this API to an exposed host.
- Idempotency handles **sequential** duplicate requests only; concurrent duplicates require further work.
- The demo's numeric precision supports two decimal places, not every ISO currency.
- Demo DB password is **not a production credential**. Use secrets management in later milestones.
- Validation and business error handling are incomplete. See [ADR-0002](docs/adr/0002-money-and-idempotency.md).
- There is no integration test with real PostgreSQL yet; add Testcontainers before claiming the system is verified.

## Development milestones
1. **Scaffold (this version):** Spring Boot module, PostgreSQL/Flyway, POST/GET simulated intake, unit tests, ADRs, local Compose.
2. **Reliability:** Testcontainers, deterministic concurrent idempotency behavior, problem details, request correlation, currency rules.
3. **Security:** OIDC/JWT auth, tenant scoping, authorization, OWASP ASVS-inspired tests, secret handling.
4. **Domain:** Double-entry simulated ledger, explicit transaction state machine, transactional outbox and Kafka.
5. **Delivery:** CI/CD, static analysis, SBOM, dependency/container scanning, reproducible builds, supply chain hardening.
6. **Operations:** Kubernetes, Helm, GitOps, OpenTelemetry, Prometheus/Grafana, SLO and load tests.

## Architecture decisions
- [ADR-0001 — Modular monolith first](docs/adr/0001-modular-monolith.md)
- [ADR-0002 — Money and idempotency boundaries](docs/adr/0002-money-and-idempotency.md)

## Validation status
The project scaffold was generated and inspected, but **Maven and dependency downloads were unavailable in the creation environment**. No claim is made that `mvn verify` has passed. Run the verification commands in an environment with Maven/dependency access and capture the results before advertising it as a tested portfolio application.

## License
MIT. Original educational implementation; not based on confidential employer source code, rules, or production data.
