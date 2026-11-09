# Enterprise Payment Platform — Learning and Interview Handbook

> **Start here.** Learn the architecture from the original problem to the current Kafka outbox integration. This is an **educational simulated payments platform**, not a regulated payments implementation. Read the **implemented / planned** distinction on every page.

## Learning contract

Each milestone must answer **WHAT**, **WHY**, **HOW**, **ALTERNATIVES**, **FAILURE MODES**, **PROOF**, **INDUSTRY CONTEXT**, and **HOW TO EXPLAIN IT IN AN INTERVIEW**. Every meaningful change to implementation or infrastructure must update the relevant architecture, scenario, ADR, evidence and operations documentation **in the same PR**. This documentation is versioned alongside the code.

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
12. [Interview workbook](interview-workbook.md) — technical questions, tradeoffs and guided explanations.

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

## Current verification baseline

On `feat/payment-ledger`, GitHub Java CI #179 and Security CI #93 passed at implementation commit `98ace4d`: **89 tests** (46 fast, 43 integration). Additional documentation-only commits are **not covered by that historical SHA**; always inspect checks on the current head. No live Kafka consumer or real payment processing exists. PR #1 and stacked PR #2 remain draft.
