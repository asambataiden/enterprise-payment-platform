# Architecture Decision Records (ADRs)

**Type:** durable design decisions and their historical evolution. **Baseline:** educational v0.1.0.

| Decision record | Interpretation |
| --- | --- |
| [0001 — Start as a modular monolith](0001-modular-monolith.md) | Implemented **initial decision**, retained as historical |
| [0004 — Microservices target and engineering standards](0004-microservices-target-and-engineering-standards.md) | **Target strategy**, not proof of an implemented microservice deployment |
| [0002 — Money and idempotency](0002-money-and-idempotency.md) | Domain and persistence rationale |
| [0003 — Simulated ledger and state machine](0003-simulated-ledger-and-payment-state-machine.md) | Incremental design chronology, with some sections superseded by the released code |

## ADR collision resolution

The target microservices strategy was given its own identifier, **ADR-0004**, while ADR-0001 continues to describe the original implemented modular monolith. Both records preserve their historical rationale.

New records: uniquely numbered, lower-case kebab-case, with title, status, date, context, decision, considered options, tradeoffs, verification and a supersedes/superseded-by link where relevant. See [documentation standards](../documentation-standards.md).

## Repeatable ADR identity and link audit

Run `python3 ops/docs/audit-adrs.py` from any working directory (the script anchors paths to its own repository root). The default mode reports numbering collisions and explicit relative Markdown links without modifying files. Run `python3 ops/docs/audit-adrs.py --strict` to require unique identifiers **after** the tracked collision has been resolved; strict mode is suitable after migration. The nonblocking audit runs in Documentation Quality CI.
