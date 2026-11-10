# Security documentation

**Status:** index for reviewed v0.1.0 educational controls; **not** a production security certification.

- [JWT resource-server trust boundary](jwt-resource-server.md) — historical baseline plus subsequent current implementation notes.
- [Merchant isolation](merchant-isolation.md) — authenticated tenant claim, ownership constraints and regression coverage.
- [Current API policy](../../README.md#api-and-authorization) — read/write/simulation permissions and disabled-by-default behavior.
- [Source security filter](../../src/main/java/dev/asambataiden/payment/shared/security/PaymentSecurityConfig.java) — current route policy.
- [Release security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42919187035) — Trivy/Gitleaks successful on v0.1.0 commit.

**Threat boundaries:** incoming merchant HTTP clients, local dev IdP, PostgreSQL tenant ownership, opt-in Kafka producer/consumer identities. The local Keycloak realm does not establish production identity governance. Remote Kafka producer provenance, operational secret rotation, deployment controls and complete assessment remain out of scope.
