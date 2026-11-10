> **HISTORICAL SNAPSHOT — Milestone 1.** This original architecture sketch predates the ledger, outbox, Kafka integration, opt-in consumer and simulation HTTP endpoint. It is retained for learning chronology, **not** the current release architecture. See [architecture navigation](architecture/README.md) and the [v0.1.0 technical case study](portfolio/technical-case-study.md).

# Architecture — Milestone 1

```mermaid
flowchart LR
    Client[Local API Client] --> Controller[Payment REST Controller]
    Controller --> Service[Payment Service]
    Service --> Repository[JPA Repository]
    Repository --> PostgreSQL[(PostgreSQL)]
    Flyway[Flyway Migrations] --> PostgreSQL
```

The application persists **simulated payment intake requests** only. `RECEIVED` does not mean authorized, captured, cleared, or settled.

## Planned bounded contexts (not implemented)
- Payment orchestration
- Double-entry ledger
- Merchant identity and authorization
- Risk/fraud assessment
- Notification and reconciliation

## Nonfunctional requirements
Security-by-design, immutable audit signals, explicit consistency boundaries, request correlation, data retention policy, dependency scanning, observability, reproducible CI, and resilience testing.
