# Enterprise Payment Platform — Learning and Interview Handbook

> **Start here.** Learn the architecture from the original problem to the current Kafka outbox integration. This is an **educational simulated payments platform**, not a regulated payments implementation. Read the **implemented / planned** distinction on every page.

## Learning contract

Each milestone must answer **WHAT**, **WHY**, **HOW**, **ALTERNATIVES**, **FAILURE MODES**, **PROOF**, **INDUSTRY CONTEXT**, and **HOW TO EXPLAIN IT IN AN INTERVIEW**. Every meaningful change to implementation or infrastructure must update the relevant architecture, scenario, ADR, evidence and operations documentation **in the same PR**. This documentation is versioned alongside the code.

### Foundational chapters (01–06)

These chapters reconstruct the originally implemented foundations. They complement
the guided labs rather than replacing architectural records or tests.

1. [01 — Problem domain and modular monolith](01-problem-domain-and-modular-monolith.md)
2. [02 — HTTP contracts and application boundaries](02-http-contracts-and-application-boundaries.md)
3. [03 — PostgreSQL, Flyway, money and idempotency](03-postgresql-flyway-money-and-idempotency.md)
4. [04 — JWT merchant security and trust boundaries](04-jwt-merchant-security-and-trust-boundaries.md)
5. [05 — Quality gates, observability and DevSecOps](05-quality-gates-observability-and-devsecops.md)
6. [06 — Ledger, atomic transitions and outbox foundations](06-ledger-atomic-transitions-and-outbox-fundamentals.md)

### Learning sequence (follow in order)

1. [Guided labs: from zero through Kafka](guided-labs.md) — hands-on reading, exercises, expected boundaries and interview checkpoints.
2. [Capstone roadmap, from project inception](../roadmap/enterprise-payment-platform.md) — scope, increments, decisions, status, later phases.
3. [Bird's-eye architecture and modules](../architecture/system-overview.md) — C4-like context, container and component views, boundaries and full data flow.
4. [Use cases, runtime and sequence diagrams](../architecture/runtime-scenarios.md) — intake, concurrent retry, capture, Kafka delivery, failure and recovery.
5. [Reusable code and module strategy](../architecture/modularity-and-reuse.md) — how to avoid repeated logic and when **not** to extract another library.
6. Existing design records: [ADR-0001](../adr/0001-modular-monolith.md), [ADR-0002](../adr/0002-money-and-idempotency.md), [ADR-0003](../adr/0003-simulated-ledger-and-payment-state-machine.md).
7. Existing focused engineering contracts: [JWT trust](../security/jwt-resource-server.md), [merchant isolation](../security/merchant-isolation.md), [outbox delivery](../architecture/outbox-delivery-contract.md), [Kafka contract](../architecture/kafka-integration-contract.md), [PR #1 review](../reviews/pr-0001-release-readiness.md).
8. [Architecture quality and test reuse](07-architecture-quality-and-test-reuse.md) — practical refactor, dependency rules and tradeoffs.
9. [Kafka consumer trust and inbox](08-kafka-consumer-trust-and-inbox.md) — real-broker contract checks, replays and trust boundaries.
10. [Kafka offsets, fail-stop recovery and polling](09-kafka-consumer-offsets-and-recovery.md) — why commit after the DB transaction, how restarts replay.
11. [Opt-in Kafka listener and typed projection](10-opt-in-kafka-listener-and-projection.md) — manual ACK, fail-stop and typed local database effects.
12. [Kafka poison evidence and recovery](11-kafka-poison-evidence-and-operator-recovery.md) — persistent observations, operator runbook and no-skip policy.
13. [Real Kafka container lifecycle and metrics](12-kafka-container-lifecycle-and-metrics.md) — successful/poison startup, stop, counters and evidence limits.
14. [Package boundaries, API error contracts and OpenAPI](13-package-refactor-error-contracts-openapi.md) — practical refactoring, schema stability and security.
15. [OpenAPI contract compatibility gate](14-openapi-compatibility-gate.md) — reviewed semantic baseline, CI failure and migration decisions.
16. [Interview workbook](interview-workbook.md) — technical questions, tradeoffs and guided explanations.

## Repeatable learning method

For each milestone:

1. Describe the **problem** without naming a technology.
2. State the **invariant** and identify where it must be enforced (client, domain, DB, broker, deployment).
3. Sketch a sequence diagram; identify trust boundaries and failure points.
4. Read the relevant classes and SQL migration **in call order**, not alphabetically.
5. Run the unit tests, focused integration tests and `mvn -B clean verify`; observe actual behavior, not just green status.
6. Deliberately predict a failure (e.g. duplicate idempotency key, wrong tenant, expired outbox lease) and find the test that proves the expected response.
7. Explain why the selected design is **appropriate for this scale and requirements**, compare one plausible alternative, and state its limitations.
8. Update learning notes, ADR if the decision changes, diagram and CI evidence. Record the exact commit for the evidence.

### Milestone page template

Copy this structure into milestone-specific notes or PR descriptions; **do not reproduce entire class definitions**:

- **Problem / actor / business outcome**
- **Functional and non-functional requirements**, assumptions and out-of-scope
- **Architecture fit**: module, interfaces, trust and transaction boundaries
- **What was built**: links to source files, tests, schema, CI
- **Why this solution**: alternatives and explicit tradeoffs
- **Walkthrough**: step-by-step request / event sequence
- **Failure and abuse cases**: expected status, retry, atomicity and telemetry
- **Best-practice context**: practice, conditions, limits, reference
- **Operational consequences**: deployment, configuration, monitoring, backup, secrets
- **Interview rehearsal**: 30-second pitch, 2-minute deep dive, follow-up questions
- **Status and evidence**: implemented/tested vs proposed, commit SHA and dated results

## What 'industrial best practice' means here

There is no single universal best architecture. Record the **requirements, constraints and quality attributes** that justify each decision. Prefer well-known approaches such as domain/module boundaries, secure defaults, database constraints, transactional outbox, idempotent consumers, immutable migration history, automated tests, observability and security gates **when they solve demonstrated risks**. Avoid untested claims of exactly-once delivery or compliance.

We use a pragmatic combination of [C4's zoom levels](https://c4model.com/introduction) and [arc42's architecture concerns](https://arc42.org/overview/) as navigation conventions, not as an excuse to write repetitive documents. The [transactional outbox](https://microservices.io/patterns/data/transactional-outbox) is the external reference for message publication reliability; our exact guarantees are established by **our own tests**.

### Recent focused chapters (15–20)

- [15 — Kafka producer contract governance](15-kafka-event-contract-governance.md)
- [16 — Kafka consumer contract compatibility](16-kafka-consumer-contract-compatibility.md)
- [17 — Strict Kafka JSON parsing](17-kafka-strict-json-parsing.md)
- [18 — Jackson dependency governance](18-jackson-dependency-governance.md)
- [19 — Merchant identifier object invariants](19-merchant-identifier-contract-consistency.md)
- [20 — Canonical Kafka UUID validation](20-kafka-canonical-uuid-validation.md)
- [21 — Merchant identifier contract policy and Unicode compatibility](21-merchant-identifier-contract-policy.md)
- [22 — Executable Draft 2020-12 JSON Schema verification](22-executable-json-schema-contract-verification.md)
- [23 — Fail-closed Kafka offset configuration](23-fail-closed-kafka-offset-configuration.md)
- [24 — Kafka ACK failure and idempotent recovery](24-kafka-ack-failure-idempotent-recovery.md)

The foundational chapters are retrospective teaching material. Recheck the current
repository implementation and tests before asserting any behavior as verified.

## Historical verification evidence

On `feat/payment-ledger`, GitHub Java CI #179 and Security CI #93 passed at implementation commit `98ace4d`: **89 tests** (46 fast, 43 integration). Additional documentation-only commits are **not covered by that historical SHA**; always inspect checks on the current head. That 89-test count is historical, not the current baseline. Subsequent changes introduced an opt-in Kafka consumer boundary and additional tests. Consult current GitHub checks and local Maven reports for up-to-date implementation evidence; there is still no real funds movement.
