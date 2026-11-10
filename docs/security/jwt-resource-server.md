> **HISTORICAL SECURITY BASELINE — Milestones 05.1–05.5.** The route table below documents that *earlier milestone* and is **not** the complete policy in v0.1.0. The released system also includes merchant-scoped `GET /api/v1/payments` search, `GET /api/v1/payments/{id}/history`, and the conditionally registered `POST /api/v1/payments/{id}/simulated-transitions` route requiring `payments:simulate` and owner validation. Optional Prometheus and OpenAPI endpoints have separate authorities and enablement controls. Consult [current security configuration](../../src/main/java/dev/asambataiden/payment/shared/security/PaymentSecurityConfig.java), the [root API table](../../README.md#api-and-authorization) and the [security documentation index](README.md). Local Keycloak identity evidence does not prove production issuer governance.

# Payment service — JWT resource-server security boundary

**Status:** Milestones 05.1–05.5 implemented and verified as an educational simulated payment platform. Deployment against a real IdP remains a separate acceptance gate.

## Authorization policy

| Route | Policy |
| --- | --- |
| `GET /actuator/health` | Public |
| `POST /api/v1/payments` | Valid JWT with `payments:write` scope and trusted `merchant_id` claim |
| `GET /api/v1/payments/{id}` | Valid JWT with `payments:read` scope and payment ownership for `merchant_id` |
| Any other path (including `/actuator/info`) | Denied |

The resource server validates bearer JWTs; it does not mint tokens, accept Basic auth or use cookie sessions. Spring Security maps JWT `scope`/`scp` claims to `SCOPE_...` authorities. Permissions alone do **not** establish tenant identity. `merchant_id` is read only from the successfully authenticated token; it must be nonblank and at most 128 characters. Cross-merchant reads return 404.

## Production configuration contract

Set the actual identity provider and audience through deployment configuration:

```text
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://identity.example.invalid/realms/payments
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=enterprise-payment-service
DB_PASSWORD=<injected-by-your-secret-manager>
```

The URLs and values above are examples, not functioning credentials. Use the issuer's real HTTPS metadata/JWK endpoints. Do not disable signature/issuer/audience checks, accept merchant identity from caller headers, or promote the test JWT signing keys into deployment. The identity provider must securely bind `merchant_id` to an approved merchant account and control scope issuance. Confirm exact claim structure, audience and key rotation against your chosen IdP.

No real IdP integration, key-rotation or production audit/secret-management approval has been completed.

## Regression evidence

- `PaymentControllerWebTest`: MVC responses, scope boundaries, missing merchant claim, anonymous rejection, default denial. Synthetic JWT authentication is **not** cryptographic verification.
- `JwtTrustValidationTest`: five real RSA-signed JWT decoder cases, including wrong issuer, audience, signature and expiry.
- `PaymentBearerTokenIT`: six end-to-end signed bearer-token tests through Spring's HTTP security filter chain, controller and PostgreSQL Testcontainers.
- `PaymentServiceIT`: nine persistence tests including merchant scoping, idempotency and concurrency.
- `PaymentMigrationUpgradeIT`: a populated V1-to-V2 migration and legacy-data isolation.

Milestone 05.5 verification: **51/51 local tests passed** and GitHub-hosted Java CI #94 and Security CI #8 succeeded at baseline `dc9d9f0`.

## Residual risks

The service is **not production-ready**. Deployment still requires a real IdP, protected network/TLS setup, managed secrets, least-privileged PostgreSQL roles, rate limits, abuse/audit controls, restoration tests and broader supply-chain assurance. If legacy V1 rows exist, reconcile ownership before real multi-tenant operations.

See [merchant isolation](merchant-isolation.md) and [PR #1 release-readiness assessment](../reviews/pr-0001-release-readiness.md).
