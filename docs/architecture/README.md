# Architecture documentation

**Type:** architecture index (arc42/C4-inspired). **Scope:** educational platform, v0.1.0 plus later documentation updates.

## Read in this order

1. [Current release architecture summary and boundaries](../portfolio/technical-case-study.md#2-technology-and-architecture) — canonical explanation of *implemented* behavior and explicit limitations.
2. [System architecture and module diagrams](system-overview.md) — **historical milestone 06.6 diagrams**; verify implementation claims against the release source.
3. [Runtime scenarios](runtime-scenarios.md) — staged request/message traces; historical content may describe incremental implementations.
4. [Messaging package boundaries](messaging-package-boundaries.md) — package contracts and architecture tests.
5. [Outbox delivery contract](outbox-delivery-contract.md) — leased claims, retries, duplicates, inbox consistency.
6. [Kafka transport contract](kafka-integration-contract.md) — earlier design increment and broker tests.
7. [Modularity and reuse](modularity-and-reuse.md) — why one deployable JAR, ports, direct-JDBC limitations.

## Readability rules

Draw separate system context, logical building-block, runtime and deployment views; do not combine unrelated layers in a single diagram. Mark **implemented**, **conditionally enabled**, **test-only**, and **planned** relationships explicitly. A Testcontainers broker is not a production deployment. The Keycloak HTTP smoke test runs without Kafka.

**Architecture decision log:** [ADR index](../adr/README.md). **Evidence:** [final release](https://github.com/asambataiden/enterprise-payment-platform/releases/tag/v0.1.0).
