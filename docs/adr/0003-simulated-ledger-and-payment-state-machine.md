# ADR-0003 — Simulation-first ledger and payment state machine

- **Status:** Accepted as a *design baseline*, pending persistence/API acceptance testing
- **Date:** 2026-11-08
- **Scope:** Educational platform only; no money movement

## Context

Payment intake currently persists `RECEIVED`. Adding a real processor, settlement or financial ledger before defining state and journal invariants would increase the chance of partial updates, duplicate postings and misleading business semantics.

The next increment requires an explicit simulated lifecycle and journal model, separate from the existing payment intake API. Tenant ownership, atomicity and idempotency remain mandatory.

## Decision

1. Implement a **pure domain** transition policy for `RECEIVED → AUTHORIZED → CAPTURED → REFUNDED`, with `FAILED` and `CANCELLED` terminal alternatives before capture. Invalid or self-transitions fail.
2. Model an immutable **balanced double-entry journal** with entry ID, owning merchant, payment ID, currency, and two or more positive debit/credit postings. Each journal entry uses one currency; `debits == credits` is a construction invariant.
3. Do **not** automatically create real ledger entries when `POST /api/v1/payments` receives a payment. `RECEIVED` is intake, not authorization or settlement.
4. Do not introduce externally callable state transitions until authentication, event authorization and transactional persistence are designed.
5. Future persistence must enforce **merchant-scoped ownership**, append-only journals, deduplication of domain events and a single transaction across payment-state transition, journal append and outbox insert. Include PostgreSQL constraint/locking tests and reversal behavior.

## Boundaries and open decisions

- This first slice is intentionally **in-memory/pure domain**; no database tables, wire endpoints, message broker, or migration are introduced.
- Entries represent *simulated* accounting; they do not represent customer funds or a regulated ledger.
- The current domain assumes at most two fractional digits. A later currency-specific minor-unit rule is required before general multi-currency accounting.
- A one-time `REFUNDED` terminal state is deliberately simplified; partial refunds, duplicate capture, voids, chargebacks, settlement and reconciliation are **not implemented**.
- Multi-currency postings, FX and cross-merchant journal entries are prohibited by the model.
- Account authorization and merchant account charts need domain policy before event handlers are introduced.

## Acceptance criteria for the next slice

- Pure-domain state-transition and ledger-invariant tests pass.
- Add an append-only `ledger_entries` and `ledger_postings` schema under Flyway with database constraints, unique domain event identifiers and merchant-scoped indexes.
- Implement transactional state transition + journal + outbox persistence and tests for duplicate events, lost updates and concurrent capture.
- Maintain **separate stacked PR** review; do not change the draft state or merge PR #1 without approval.
