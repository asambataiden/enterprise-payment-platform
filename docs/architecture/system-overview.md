> **HISTORICAL AS-BUILT SNAPSHOT — Milestone 06.6.** This page documents an earlier implementation increment, **not** the final v0.1.0 component inventory. The release includes Flyway V1–V9, an opt-in simulated-transitions HTTP controller, an opt-in scheduled outbox relay and an opt-in Kafka listener/consumer path that were added after this snapshot. The authenticated local HTTP demo runs without Kafka. For current implementation scope, start with the [technical case study](../portfolio/technical-case-study.md) and inspect the [release-tagged source](https://github.com/asambataiden/enterprise-payment-platform/tree/v0.1.0). The diagrams and prose below are intentionally preserved as historical evidence.

# System architecture — bird's-eye view to modules

**As-built baseline:** 06.6 on `feat/payment-ledger`. **Diagram convention:** solid connectors = current code/boundary; dashed connectors = future/optional actors or not yet wired integration. Testcontainers Kafka is real Kafka **only in tests**, not a running production cluster.

## Why diagrams at several levels?

An architecture diagram should answer a question. A context view explains **who uses the system**, a container view explains **where software executes and data lives**, a component view explains **how the Java modules cooperate**, and runtime sequences explain **what happens in order**. Do not draw one overwhelming giant diagram and expect it to communicate every risk. We follow the ideas of [C4](https://c4model.com/introduction) and use [arc42](https://arc42.org/overview/) to organize quality goals, decisions and risks.

## View A — System context

```mermaid
flowchart LR
  M[Merchant application] -->|JWT bearer HTTP| P[Enterprise Payment Platform\nSimulated intake API]
  I[Identity provider\nDeployment integration pending] -.->|issuer / keys| P
  P -.->|optional event publishing| K[Kafka broker\nVerified only with Testcontainers]
  K -.->|future subscriber| S[Downstream event consumer\nNot implemented]
  P -->|durable transactions| DB[(PostgreSQL)]
```

**Trust boundaries:** merchants are untrusted HTTP clients; JWTs must be cryptographically validated, scoped and mapped to an authorized merchant identity; the DB enforces uniqueness and ownership FKs; Kafka security must be configured for the real deployment. No actual payment processor exists.

## View B — Container / deployable view (as built)

```mermaid
flowchart TB
  Client[Merchant HTTP client]
  subgraph Java[One Java 25 / Spring Boot 4 application]
    Security[Resource Server security]
    API[Payment REST Controller]
    App[Payment application use cases]
    Lifecycle[Internal simulated lifecycle]
    Ledger[Ledger domain + JDBC adapter]
    Relay[Outbox dispatcher + optional Kafka producer]
    Security --> API --> App --> Lifecycle
    Lifecycle --> Ledger
    Lifecycle --> Relay
  end
  DB[(PostgreSQL 17\nFlyway V1-V6)]
  Broker[(Kafka broker\nTestcontainers integration only)]
  Client --> Security
  App --> DB
  Lifecycle --> DB
  Ledger --> DB
  Relay --> DB
  Relay -.optional transport.-> Broker
```

The `PaymentOutboxDispatcher` is manually invoked. `SupervisedPaymentOutboxWorker` is a bounded helper, **not** a configured scheduler. Kafka is disabled by default. The live Kafka consumer is not implemented. Runtime identity provider, cloud deployment, managed secrets, audit and alert infrastructure are not established by this test suite.

## View C — Logical module and dependency map

```mermaid
flowchart LR
  API["payments.api\nPaymentController / DTOs"]
  SEC["shared.security\nJWT / authorization"]
  APP["payments.application\nPaymentService / PaymentStore port"]
  DOMAIN["payments.domain\nPaymentStateMachine"]
  PERSIST["payments.infrastructure.persistence\nPaymentJdbcWriter / repository"]
  TRANS["payments.application\nSimulatedPaymentTransitionService"]
  LD["ledger.domain\nJournalEntry / Posting"]
  LA["ledger.application\nSimulatedLedgerService"]
  OB["payments.outbox\nOutbox dispatcher / inbox / Kafka adapter"]
  PG[(PostgreSQL)]
  KF[(Kafka optional)]
  API --> APP
  SEC --> API
  APP --> PERSIST
  APP --> DOMAIN
  TRANS --> DOMAIN
  TRANS --> LA
  TRANS --> PG
  LA --> LD
  LA --> PG
  PERSIST --> PG
  OB --> PG
  OB -.-> KF
```

**Important nuance:** `PaymentService` depends on the `PaymentStore` **port**; the concrete JDBC writer supplies it. The simulated transition and ledger services presently use `JdbcTemplate` directly. That is acceptable in a small learning increment, but means **not every component follows full ports-and-adapters layering yet**. Do not call it a fully implemented hexagonal architecture.

### Module responsibilities and why they exist

| Module | Owns | Why separate | Representative code |
| --- | --- | --- | --- |
| API / DTOs | HTTP shapes and status codes | decouple wire contracts from domain rules | `PaymentController` |
| Security | token trust, access control, tenant claim validation | clients must not choose tenant identity | `PaymentSecurityConfig` |
| Application | use-case orchestration and transactions | domain logic not tied to controller | `PaymentService`, `SimulatedPaymentTransitionService` |
| Domain | permitted transitions and accounting invariants | pure testable business rules | `PaymentStateMachine`, `JournalEntry` |
| Persistence | durable atomic inserts / tenant lookups | DB arbitrates races | `PaymentJdbcWriter`, Flyway |
| Ledger | simulated balanced journals | capture semantics distinct from intake | `SimulatedLedgerService` |
| Outbox/inbox | durable message dispatch, retry and replay protection | DB and broker cannot be one ordinary transaction | `PaymentOutboxDispatcher`, `PaymentInbox` |
| Kafka adapter | serialized keyed event and broker ACK | vendor-specific transport behind an interface | `KafkaPaymentEventPublisher` |
| Crosscutting | correlation, JSON errors, architecture tests | consistency without duplicating API code | `shared.observability`, `PaymentApiExceptionHandler`, ArchUnit |

## View D — Data ownership, evolution and invariants

```mermaid
erDiagram
  PAYMENTS ||--o{ LEDGER_ENTRIES : "merchant / payment reference"
  LEDGER_ENTRIES ||--|{ LEDGER_POSTINGS : "journal lines"
  PAYMENTS ||--o{ PAYMENT_OUTBOX : "state events"
  PAYMENT_OUTBOX ||--o{ PAYMENT_INBOX : "consumer receipts"
  PAYMENTS {
    uuid id PK
    string merchant_id
    string idempotency_key
    decimal amount
    string currency
    string status
  }
  LEDGER_ENTRIES {
    uuid id PK
    string merchant_id
    uuid payment_id FK
    uuid event_id
    bool finalized
  }
  LEDGER_POSTINGS {
    uuid entry_id FK
    int line_number
    string side
    decimal amount
  }
  PAYMENT_OUTBOX {
    uuid id
    string merchant_id
    uuid payment_id FK
    string target_state
    int delivery_attempts
    timestamp dead_lettered_at
  }
  PAYMENT_INBOX {
    string consumer_name
    string merchant_id
    uuid event_id FK
  }
```

Invariants are more important than diagrams: **tenant-scoped reads**, composite merchant/idempotency uniqueness, enforced payment ownership on journals/outbox, two or more journal postings with debits = credits, finalized journals immutable, event identity unique within each tenant, and consumer receipts unique per consumer+tenant+event. A schema diagram does **not** prove all constraints—read V1–V6 migration SQL and their PostgreSQL ITs.

## View E — Security and operations overlays (not yet fully deployed)

- **Authenticity:** JWT signature, issuer, audience and expiration validation are tested; a real IdP has not been integrated.
- **Authorization:** scopes and merchant ownership; internal simulation transition service is **not** a public endpoint and needs an authorized event source before exposure.
- **Database:** foreign keys, unique constraints, transactions, immutable journal triggers. Production least-privilege DB roles and RLS are not implemented.
- **Messaging:** optional TLS/SASL-ready publisher; test Kafka broker runs locally with PLAINTEXT in Testcontainers. Production broker authentication/ACL smoke tests are future work.
- **Observability:** correlation IDs and ECS JSON logs are present; production metrics, tracing and alerts are future work.
- **Risk:** outbox guarantees at-least-once *attempt semantics*, not exactly-once remote effects. A slow publisher can outlive the 30-second lease.

## How to read the project from scratch

Start at `PaymentController` → `PaymentService` → `PaymentStore` → PostgreSQL. Then trace `SimulatedPaymentTransitionService` → `PaymentStateMachine` → `SimulatedLedgerService` + `payment_outbox`. Finally read `PaymentOutboxDispatcher` → `PaymentEventPublisher` → `KafkaPaymentEventPublisher`; reverse the flow for the `PaymentInbox` local-effect helper. Use [runtime scenarios](runtime-scenarios.md) to follow each flow and [roadmap](../roadmap/enterprise-payment-platform.md) to understand why the order matters.

**Never put the entire enterprise target picture into an “as implemented” diagram without clearly labeling hypothetical components.**
