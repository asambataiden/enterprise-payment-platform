# ADR-0001: Microservices target architecture and service-level engineering standards

- **Status:** Accepted (supersedes the initial modular-monolith wording)
- **Date:** 2026-11-08
- **Scope:** Educational simulated payment platform; no real funds movement

## Context

The Enterprise Payment Platform is intended to showcase enterprise Java engineering, event-driven microservices, cloud-native infrastructure and DevSecOps. The initial repository provides a single working payment-intake deployment with Java 25, Spring Boot 4, PostgreSQL and Testcontainers. A single service is not the same as choosing a system-wide monolithic architecture.

## Decision

1. **Microservices as the target.** Begin with one independently deployable Payment Service, then introduce separate Merchant, Ledger, Fraud/Risk, Notification and Reporting services when their domain contracts and workflows justify them. The platform will have multiple deployable services with bounded contexts, independently managed data and explicit integration contracts.
2. **Feature-oriented structure inside every service.** Use packages for `api`, `application`, `domain`, and `infrastructure` inside a service-specific bounded context (e.g. `dev.asambataiden.payment.payments`). Clean layers inside one microservice do not imply a system-wide monolith.
3. **Ownership and communication.** Each service owns its persistence and migrations; no shared writable database. Prefer asynchronous domain/integration events over distributed synchronous request chains. When added, Kafka events require schema versioning, consumer idempotency and a transactional outbox. Do not claim these capabilities before implementation.
4. **Dependency boundaries.** Controllers depend on application use cases. Domain concepts do not depend on Spring Web, JDBC or JPA. Infrastructure implements meaningful application-defined ports. Avoid overengineering interfaces or independently deployed services with no independent business purpose.
5. **Naming and documentation.** Lowercase Java package names, PascalCase type names, descriptive camelCase methods, snake_case relational identifiers, immutable `Vn__description.sql` Flyway migrations and Conventional Commits. Maintain ADRs when introducing new architectural decisions.
6. **Test lifecycle.** Surefire runs fast `*Test.java` tests (unit, web slice, architecture). Failsafe runs PostgreSQL and other `*IT.java` integration tests in `mvn verify`, without silently skipping required Docker tests. Maintain separate contract and end-to-end suites as integrations appear.
7. **Observability.** Use structured SLF4J logging, correlation and trace context, metrics and distributed tracing at service boundaries. Never log secrets, raw idempotency keys, payment request bodies or sensitive payment details. Return safe RFC 9457 Problem Details for errors.
8. **Security and correctness.** PostgreSQL uniqueness arbitrates concurrent payment intake idempotency. Adopt least-privilege identity, defense-in-depth, explicit validation and dependency scanning as relevant capabilities are introduced. This simulated platform is not a regulated payment processor.
9. **Delivery.** Every service must build, test, package and deploy independently. Incremental pull requests must pass relevant quality gates; `main` remains stable.

## Implementation sequence

- **Increment A:** Refactor existing Payment Service code into feature-oriented architectural packages without changing API contracts or database schema. Add architecture tests.
- **Increment B:** Separate fast and Docker-backed tests with Maven Surefire/Failsafe; verify both suites.
- **Increment C:** Verify RFC 9457 responses over HTTP, add correlation and structured logging, and improve observability.
- **Increment D:** Build Java 25 CI with Docker-backed tests and security/quality checks.
- **Later:** Introduce a second deployable microservice when an explicit cross-context use case and integration contract exist. Add event-driven architecture, service-owned databases, container builds and Kubernetes/GitOps incrementally.

## Consequences

This avoids the operational cost of many empty services while keeping the target architecture explicitly microservices-based. The first payment service can be deployed independently, and new services are added through deliberate domain boundaries rather than arbitrary technical splits. No microservice is considered production-ready purely because its basic test suite passes.
