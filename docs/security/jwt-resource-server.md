# Payment Service — JWT resource server boundary (increment 05.1)

## Implemented

The Payment Service now uses Spring Security's **stateless OAuth2 JWT resource server** and denies unmatched routes by default. The service does not issue tokens or accept HTTP Basic authentication.

| Route | Policy |
|---|---|
| `GET /actuator/health` | Public health check |
| `POST /api/v1/payments` | Verified bearer JWT with `payments:write` scope |
| `GET /api/v1/payments/{id}` | Verified bearer JWT with `payments:read` scope |
| All other routes, including `/actuator/info` | Denied |

An OAuth2 `scope` or `scp` claim mapped by Spring Security to `SCOPE_payments:read` and `SCOPE_payments:write` supplies permissions. These permission strings are **not** merchant identity and do **not** implement object/tenant authorization.

## Runtime configuration — mandatory

For a running application, configure the trusted OIDC provider. Spring Boot requires an issuer or JWK Set URI to configure JWT decoding. A recommended deployment environment uses:

```text
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://identity.example.invalid/realms/payments
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=payment-service
```

Replace example values with the real provider's issuer URL and the audience actually present in the access tokens. Do not use the sample URL, self-signed bearer claims or arbitrary user-controlled headers as proof of identity. Configure resource-server trust, HTTPS, audience validation and key rotation against an approved identity provider.

Without a configured decoder, startup may fail closed; the integration tests deliberately supply a mocked `JwtDecoder` because they call the Java application service directly. No JWT signing keys or credentials are committed to the repo.

## Coverage

MVC tests cover unauthenticated GET/POST denials, insufficient read/write scopes, and deny-by-default routing. Existing business-error tests run with a mock authenticated principal, preserving coverage of 400/404/409 response contracts. PostgreSQL integration tests still exercise the application and persistence layers.

## Critical next increment: merchant ownership

**Not yet implemented:** the persisted `payments` table currently has no merchant ownership identifier. A valid token with `payments:read` can potentially read another merchant's record if its UUID is known. Before any realistic multi-tenant or sensitive-data deployment:

1. Introduce a trusted stable `merchant_id` as part of the authenticated application context and payment persistence model.
2. Scope idempotency uniqueness to `(merchant_id, idempotency_key)`, with a versioned Flyway migration and explicit migration plan for existing rows.
3. Load payments only with merchant ownership constraints and test cross-merchant reads and retries.
4. Require authentication for all externally exposed operations; test expired, invalid-signature, wrong-issuer and wrong-audience JWTs with a real test decoder.
5. Implement rate limiting, suitable response hardening and independent security scans.

**Scope:** Educational simulated payments only. This increment does not establish production readiness.
