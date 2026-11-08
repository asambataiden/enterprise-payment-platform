# ADR-0001: Modular monolith, package boundaries and verification strategy

- **Status:** Accepted
- **Date:** 2026-11-08
- **Scope:** Educational simulated payment platform (no real funds movement)

## Context

The initial payment intake implementation demonstrates a working Java 25 / Spring Boot 4 / PostgreSQL stack. Its original flat `payment.payment` package mixes HTTP, application logic, persistence and DTOs. Unit tests and database integration tests currently run together through Surefire. The repository needs explicit conventions before adding further features.

## Decision

1. **Modular monolith first.** Organize code by business capability, beginning with `dev.asambataiden.payment.payments`, then by architectural responsibility within the feature: `api` (REST + DTOs), `application` (use cases and transaction boundaries), `domain` (business concepts and invariants) and `infrastructure.persistence` (database adapters). Reserve `shared` for truly cross-cutting technical facilities; do not create a generic utility dumping ground.
2. **Boundaries and dependencies.** Controllers depend on application services. Domain logic must not depend on Spring Web or PostgreSQL. Infrastructure can depend on the domain and application-defined persistence ports. Introduce ports only where they enforce a meaningful boundary; avoid interfaces for every class.
3. **Naming.** Use lowercase Java packages, PascalCase types, descriptive camelCase methods, `snake_case` SQL identifiers, `Vn__description.sql` immutable Flyway migrations, and Conventional Commits. Group tests by the code they exercise, with test names describing behavior.
4. **Test suites.** Run fast JUnit/Mockito, API-slice and ArchUnit tests (`*Test.java`) via Maven Surefire. Run Docker-backed PostgreSQL integration tests (`*IT.java`) via Maven Failsafe during `mvn verify`. Testcontainers must not be silently skipped when required by the verification pipeline. Later, contract and end-to-end tests belong in their own explicit jobs.
5. **Observability.** Use SLF4J structured events, request correlation and trace context, Micrometer metrics, and OpenTelemetry instrumentation when introduced. Never log credentials, request bodies, raw idempotency keys, or potentially sensitive payment information. Keep HTTP error output aligned with RFC 9457.
6. **Security and correctness.** PostgreSQL uniqueness arbitrates concurrent idempotency inserts. Prefer explicit validation and deny-by-default configuration. This project is a simulated payment workflow, not a PCI-compliant or production-ready processor.
7. **Delivery.** Each change requires compilation, appropriate automated tests, documentation updates and CI quality gates before merge. Use focused commits and keep `main` stable.

## Migration plan

- **Increment A — architecture:** Move existing classes into feature packages while preserving public HTTP endpoints and Flyway schema; add tests or ArchUnit checks for intended boundaries.
- **Increment B — test lifecycle:** Rename database tests to `*IT`, configure Surefire/Failsafe and verify both fast and full suites.
- **Increment C — HTTP/observability:** Verify Problem Details at the HTTP boundary and introduce safe, correlated structured logging.
- **Increment D — CI:** Java 25 GitHub Actions running `mvn -B clean verify`, with Docker-enabled integration testing and security/quality checks.

Each increment must pass locally before the next. Do not prematurely split the application into microservices.

## Consequences

The structure introduces a small amount of organization and some additional test/build configuration. In exchange, the code becomes easier to review, extend, test in isolation and evolve into a larger portfolio system. Future decisions that materially change business or technical boundaries should get separate ADRs.
