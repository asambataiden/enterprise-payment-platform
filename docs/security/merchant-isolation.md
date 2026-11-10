# Milestone 05.2 — Merchant ownership and data isolation

## Contract

- The Payment API derives a stable `merchant_id` claim from the **validated JWT**, never from request JSON or a caller-supplied merchant header.
- Tokens without a nonblank merchant identifier (at most 128 characters) receive HTTP 403.
- JWT `payments:write` and `payments:read` scopes are still required for create and retrieve.
- Payment retrieval filters by both authenticated `merchant_id` and payment UUID. Cross-merchant requests return the same HTTP 404 as unknown payment IDs, minimizing existence disclosure.
- Idempotency uniqueness is scoped to `(merchant_id, idempotency_key)`; simultaneous requests from the **same** merchant remain serialized by PostgreSQL uniqueness.

## Schema upgrade

Migration `V2__merchant_payment_ownership.sql` adds a nullable `merchant_id` to preserve existing V1 records, drops the global idempotency-key unique constraint and replaces it with a composite unique index.

**Legacy rows remain unassigned and cannot be retrieved by merchant-scoped API queries.** Do not automatically ascribe them to an arbitrary merchant. For a real deployment with existing customer data, a separate audited backfill and reconciliation procedure is necessary. The field is intentionally nullable for historical compatibility; the public application must never write new unassigned payments.

## Trust boundary and operating requirements

The JWT issuer **must** be an approved IdP that issues `merchant_id` only after correctly binding a caller to the merchant account. Configure JWT issuer, signature verification, audience, allowed signing algorithms and access-token lifetime for the deployed identity system.

Do not interpret successful synthetic MVC requests (`jwt()`) as proof of cryptographic token verification. Add dedicated tests with real signed JWTs, wrong issuer/audience/signature and expired tokens when the identity provider contract has been selected.

Defense in depth: merchant isolation is currently enforced by repository query predicates. PostgreSQL Row Level Security, database least-privilege roles, authenticated audit trails and cross-service authorization are separate deployment hardening tasks.

## Milestone 05.3 verification additions

- `JwtTrustValidationTest` generates ephemeral RSA key pairs and signs real JWS access-token fixtures, then checks valid claims, wrong issuer, wrong audience, expired token and untrusted signing key using a real Nimbus JWT decoder.
- `PaymentMigrationUpgradeIT` explicitly creates an old V1 schema, inserts an existing payment, upgrades to V2, verifies that the row remains unassigned and unseen by merchant-scoped reads, and confirms the new composite idempotency namespace.
- These tests **do not yet validate a deployed issuer's JWKS discovery/configuration, live HTTP bearer-token chain, or access-token claim issuance policy**; they are focused regression coverage, not an identity-provider certification.

**Status:** Under CI and local verification. This is a simulated payment platform, not a regulated production processor.
