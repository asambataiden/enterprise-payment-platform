# ADR-0001: Start as a modular monolith

- Status: Accepted for Milestone 1
- Date: 2026-11-08

## Context
The portfolio must demonstrate defensible domain boundaries and reliable transaction semantics without premature distributed complexity.

## Decision
Use one Spring Boot application, with features grouped into domain-oriented packages. Start with a simulated payment-intake bounded context. Keep persistence inside the service boundary; expose an explicitly versioned HTTP API.

## Consequences
- Local development and debugging remain inexpensive.
- The API and PostgreSQL schema can be tested before introducing messaging.
- No assumption is made that microservices are inherently superior.
- Future services (ledger, risk, notifications) must be extracted only after an explicit architectural justification and reliable event publishing design.
