> **ARCHIVED REVIEW — Pre-merge assessment dated 2026-11-08.** Its draft-state and merge-hold instructions were correct for the original assessment moment and **do not apply to the repository's present state**. [PR #1](https://github.com/asambataiden/enterprise-payment-platform/pull/1) and [PR #2](https://github.com/asambataiden/enterprise-payment-platform/pull/2) were subsequently merged; the final [v0.1.0 release](https://github.com/asambataiden/enterprise-payment-platform/releases/tag/v0.1.0) has passing [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42919187324) and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42919187035). Historical evidence and decision text below are deliberately preserved.

# PR #1 — Security and merge-readiness assessment (Milestone 05.6)

- **Assessment date:** 2026-11-08
- **Pull request:** https://github.com/asambataiden/enterprise-payment-platform/pull/1
- **Source branch:** `feat/payment-reliability`
- **Verification baseline before this review:** `dc9d9f0a252a04ce43abf90276f2c46656c460fe`
- **Decision:** **Eligible for a simulation-only engineering review, but not automatically approved for merge. Not production-ready.**
- **Change policy:** Keep PR #1 as draft. Do not merge or mark ready without explicit owner approval.

## Evidence reviewed

| Control | Evidence | Result |
| --- | --- | --- |
| Local Java/Maven verification | 2026-11-08 `mvn -B clean verify` user-provided execution at `dc9d9f0` | **51/51 passed** (35 Surefire + 16 Failsafe), zero skipped |
| Hosted Java verification | [GitHub Actions Java CI #94](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42508084389) | **Success**; Java 25, Maven and PostgreSQL Testcontainers |
| Dependency scan | [Security CI #8](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42508084202) | **Success**; Trivy scans packaged JAR and blocks HIGH/CRITICAL reported findings |
| Committed-secret scan | Same Security CI #8 | **Success**; Gitleaks history scan |
| Cryptographic token validation | `JwtTrustValidationTest` (5 cases) | Signed RSA JWT; issuer, audience, expiration, untrusted signature |
| Real bearer-token HTTP enforcement | `PaymentBearerTokenIT` (6 cases) | Filter chain, scope enforcement, tenant isolation and negative bearer-token cases |
| Merchant ownership and idempotency | `PaymentServiceIT` + `PaymentControllerWebTest` | Merchant-scoped reads, unique `(merchant_id,idempotency_key)`, concurrent same-tenant retries |
| Data upgrade | `PaymentMigrationUpgradeIT` | Populated V1-to-V2 upgrade preserves legacy rows without inventing ownership |

**Evidence limit:** A passing test suite and scanners do not establish compliance, deployment hardening, or financial-processing readiness. CI status belongs to the stated baseline commit and must be reconfirmed for the final reviewed head.

## Implementation confirmed

1. The public API requires bearer JWT authentication with `payments:write` for creation and `payments:read` for retrieval; unlisted routes are denied.
2. Payment ownership is derived from the validated JWT `merchant_id` claim. The service and repository require merchant identity, and retrieving a different merchant's payment returns 404.
3. PostgreSQL enforces concurrent-safe idempotency per merchant using a composite unique index plus an atomic insert; same-key changed payload remains a conflict.
4. Database credentials use explicit `DB_PASSWORD`. Compose refuses an unset/empty value; **the environment must still supply a strong genuine secret**.
5. HTTP Problem Details, request correlation and architecture tests remain covered.
6. Separate Java verification, packaged-dependency scanning and Git-history secret scanning run in GitHub Actions.

## Remaining decisions and risks

| Priority | Finding / limitation | Required outcome |
| --- | --- | --- |
| **Blocker — any real deployment** | The approved identity provider has not been selected or tested against the deployed runtime. Production issuer URL, audience, JWK rotation, access-token issuance and the authority binding of `merchant_id` are not evidenced. | Configure approved OIDC/JWT provider; test real access tokens, wrong issuer/audience, revoked/rotated keys, issuer outages and merchant authorization policy. |
| **Blocker — any real deployment** | Secrets management, least-privileged production database roles, TLS/network restrictions, backup/restore and deployment isolation are not evidenced. | Establish managed secrets/workload identity and an audited least-privilege deployment; test restoration and enforce encrypted network paths. |
| **Blocker — any real deployment with pre-V2 data** | Legacy V1 rows have nullable `merchant_id` and are intentionally unreachable via scoped API queries. | Reconcile/backfill historical ownership through a separately audited plan; do not silently assign tenants or expose unowned records. Consider NOT NULL enforcement once reconciliation is complete. |
| **Blocker — regulated payment processing** | The service records simulated `RECEIVED` events; no settlement, reconciliation, fraud screening, safeguarding, ledger controls or real payment integrations. | Keep non-production educational scope, or initiate a separate regulatory, domain, threat-model and operational approval program. |
| **Review gate — simulation merge** | Branch-protection configuration, required status checks, reviewer sign-off, and final PR head checks have not been verified in this assessment. | Confirm required checks and review permissions, inspect diff and CI against exact merge head; obtain explicit owner approval before taking out of draft/merging. |
| **High — deployment hardening** | Rate limiting, input-size enforcement, access audit policy, metrics/alerts, disaster recovery and abuse controls are not demonstrated. | Define SLOs and threat model; add negative abuse, load and recovery tests before exposing service. |
| **High — supply chain** | Trivy scans the packaged JAR and Gitleaks scans Git history, but there is no evidenced SBOM publication, artifact signature, image scan, SAST/CodeQL gate or third-party action SHA pinning. | Introduce software-supply-chain controls in a separately reviewed increment; ensure scanners fail closed and findings have owners. |
| **Medium — API semantics** | Idempotency retention/lifecycle, response behavior under replay and API specification/versioning need a durable public contract. | Publish OpenAPI contract, retention plan and explicit replay invariants. |
| **Medium — defense in depth** | Tenant filters reside in application/persistence code; DB row-level security is not implemented. | Consider RLS and a tenant-context design if the service will host real multi-tenant sensitive data. |

## Explicit merge gate

### Simulation-only baseline

- [x] 51 local Java tests passed at verification baseline.
- [x] GitHub-hosted Java CI passed.
- [x] Trivy dependency security gate passed.
- [x] Gitleaks Git-history secret gate passed.
- [x] Documented and tested tenant ownership, signed JWTs, and populated Flyway upgrade.
- [x] Removed default database password from the application and Compose configuration.
- [ ] Reconfirm all required checks at the **final** PR head SHA.
- [ ] Confirm repository protection, reviewer policy, and acceptable educational scope.
- [ ] Receive explicit approval from the repository owner to remove the draft status or merge.

### Real or sensitive-data deployment

- [ ] Validate identity-provider configuration, token claims and key rotation in the target environment.
- [ ] Complete secrets, database privilege, network and audit controls.
- [ ] Reconcile any legacy tenantless rows and document recovery procedures.
- [ ] Implement abuse prevention, secure operations, additional security testing and supply-chain controls.
- [ ] Obtain domain/security and regulatory sign-off as applicable.

**Final disposition:** **Keep PR #1 in draft.** The branch is a validated portfolio engineering baseline; neither this assessment nor its CI results authorize production deployment or a merge.