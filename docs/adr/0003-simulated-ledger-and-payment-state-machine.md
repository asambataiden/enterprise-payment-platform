# ADR-0003 — Simulation-first ledger and payment state machine

- **Status:** Domain model and internal PostgreSQL persistence implemented; HTTP API and payment-state integration remain out of scope
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

- The initial domain slice (06.1) was pure domain. Milestone 06.2 adds **Flyway V3** tables, a transactional internal ledger service and PostgreSQL integration tests; no wire endpoints, payment transition execution or message broker are introduced.
- Entries represent *simulated* accounting; they do not represent customer funds or a regulated ledger.
- The current domain assumes at most two fractional digits. A later currency-specific minor-unit rule is required before general multi-currency accounting.
- A one-time `REFUNDED` terminal state is deliberately simplified; partial refunds, duplicate capture, voids, chargebacks, settlement and reconciliation are **not implemented**.
- Multi-currency postings, FX and cross-merchant journal entries are prohibited by the model.
- Account authorization and merchant account charts need domain policy before event handlers are introduced.

## Milestone 06.2 database invariants

- `ledger_entries` and `ledger_postings` are persisted transactionally with a one-way `finalized` flag. A journal is not allowed to commit without postings and balance.
- PostgreSQL deferred constraint triggers reject unbalanced and empty journals, while mutation triggers prohibit updates/deletes and late inserts after finalization.
- `(merchant_id, event_id)` is unique and `(merchant_id, payment_id)` references the corresponding payment owner. Retrieval always filters by authenticated merchant identity at the service boundary.
- This milestone does **not** mutate `payments.status` or create an outbox. Event authorization and public ledger APIs must not be inferred from the internal service.
- Verified in hosted Java CI #121 (46 fast + 24 PostgreSQL integration tests, **70 total**); hosted Security CI #35 succeeded.

## Acceptance criteria for the next slice

- Pure-domain state-transition and ledger-invariant tests pass.
- Review ledger/payments currency consistency, authorized account plans, journal-event semantics and transaction roles with explicit domain rules.
- Implement transactional state transition + journal + outbox persistence and tests for duplicate events, lost updates and concurrent capture.
- Maintain **separate stacked PR** review; do not change the draft state or merge PR #1 without approval.

## Milestone 06.3: internal atomic simulation workflow

- Flyway V4 permits all states enumerated by the pure domain state machine and creates `payment_outbox` with an event identifier unique **per merchant** and a merchant-owned payment reference.
- The internal `SimulatedPaymentTransitionService.advance(merchantId, paymentId, eventId, target)` locks the merchant-owned payment row with `FOR UPDATE`; it validates transitions before changing `status`.
- Retried events return their prior target only when merchant, payment, event ID and target match; reuse with different payment or target is rejected.
- A simulated `CAPTURED` transition creates balanced ledger postings using the **persisted** payment amount and currency, not user-supplied ledger amounts or account IDs.
- The state update, optional capture journal and pending outbox record share **one** database transaction. Failed ledger persistence and failed event validation must roll back the state change; no external publisher or money transfer is wired.
- `AUTHORIZED`, `FAILED`, `CANCELLED` and `REFUNDED` transitions currently write an outbox record but **no financial journal**. Refund accounting and reversals are intentionally deferred: do not claim the ledger models settlement, reversals or payment obligations.
- No public endpoint or authorization adapter invokes this workflow. Before attaching an external webhook/command consumer, define trusted event provenance, replay controls, state-event mapping, transaction isolation, and an outbox publisher with at-least-once delivery and consumer deduplication.

### 06.3 acceptance

- Atomic authorization and capture, idempotent replay, cross-merchant rejection, invalid transitions, concurrent update serialization, and rollback on ledger conflict covered using PostgreSQL Testcontainers.
- Verify Java and security CI at the final commit head before declaring 06.3 complete.
