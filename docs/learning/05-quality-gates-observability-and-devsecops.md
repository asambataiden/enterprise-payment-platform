# Chapter 05 — Test strategy, observability and DevSecOps quality gates

**Status:** Retrospective guided learning; scan results are commit-specific.

## Testing pyramid and the limits of each layer

| Layer | What it establishes | What it does not establish |
| --- | --- | --- |
| Pure JUnit domain tests | State machine and amount invariants | Database locking or Kafka behavior |
| MVC/security tests | Request binding and auth responses | Production identity-provider operation |
| PostgreSQL Testcontainers | Constraints, migrations, transactions, concurrency | Multi-region recovery |
| Kafka Testcontainers | Actual produce/consume and partition semantics | Production ACLs/TLS/cluster topology |
| ArchUnit | Static dependency rules | Correct runtime behavior |
| OpenAPI/JSON tests | Selected contract invariants | Complete schema/spec validation |
| CI scanners | Configured scan scope | End-to-end compliance or absence of vulnerabilities |

## Why quality gates?

A reviewable change should carry a reproducible test, threat/failure analysis and evidence tied to the exact commit. Build failures must stop merging rather than be hidden by `-DskipTests`.

Run `mvn -B --no-transfer-progress clean verify` and inspect both Surefire and Failsafe summaries. The integration suite needs a working Docker API.

## Observability

Follow a request correlation ID through HTTP response and structured logs. Logging must avoid payment secrets, personal data and bearer tokens. Metrics, logs and traces answer different questions; a passing test or available actuator endpoint is not proof of production alerting.

## Supply-chain and release controls

Inspect GitHub Actions workflow files for Java build, secrets scanning and image/dependency scans. Review scan exclusions, failure handling, artifact provenance and whether a current-commit check actually ran. SBOM generation, signing, SLSA-style provenance and deployment enforcement must be implemented and verified before being claimed.

## Exercise

Choose three failure modes: bad JWT, failed outbox dispatch, duplicate Kafka event. For each trace: test assertion → expected status/retry → log/metric → operational response. Draw the validation sequence from developer commit to controlled deployment, labeling implemented checks versus planned controls.

**Interview:** Industrial quality is traceable risk reduction. A green scan is evidence about the configured scanner and rules at one commit; it is not a security guarantee.
