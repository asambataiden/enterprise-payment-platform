# Architecture Decision Records (ADRs)

**Type:** durable design decisions and their historical evolution. **Baseline:** educational v0.1.0.

| Decision record | Interpretation |
| --- | --- |
| [0001 — Start as a modular monolith](0001-modular-monolith.md) | Implemented **initial decision**, retained as historical |
| [0001 — Microservices target and engineering standards](0001-modular-monolith-and-testing-strategy.md) | **Target strategy**, not proof of an implemented microservice deployment |
| [0002 — Money and idempotency](0002-money-and-idempotency.md) | Domain and persistence rationale |
| [0003 — Simulated ledger and state machine](0003-simulated-ledger-and-payment-state-machine.md) | Incremental design chronology, with some sections superseded by the released code |

## Identified documentation debt: duplicate ADR-0001

Two existing records have the same **ADR-0001** identifier but different decisions. This is a genuine collision. **Do not silently rename or overwrite historical records:** first audit all inbound links and prepare a separate mechanical migration, assigning the target microservices strategy a unique next identifier (for example `0004`), then update references and validate.

Until resolved, distinguish these records by their **full filename**, not the duplicate ID. In the current code the actual deployment shape is a single Spring Boot application (modular monolith); a future microservices target is a proposal, not an as-built fact.

New records: uniquely numbered, lower-case kebab-case, with title, status, date, context, decision, considered options, tradeoffs, verification and a supersedes/superseded-by link where relevant. See [documentation standards](../documentation-standards.md).

## Repeatable ADR identity and link audit

Run `python3 ops/docs/audit-adrs.py` from any working directory (the script anchors paths to its own repository root). The default mode reports numbering collisions and explicit relative Markdown links without modifying files. Run `python3 ops/docs/audit-adrs.py --strict` to require unique identifiers **after** the tracked collision has been resolved; strict mode currently fails by design. The nonblocking audit runs in Documentation Quality CI.
