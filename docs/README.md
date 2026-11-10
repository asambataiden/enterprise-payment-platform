# Documentation hub

> **Current baseline:** [published v0.1.0](https://github.com/asambataiden/enterprise-payment-platform/releases/tag/v0.1.0). This is an **educational simulated-payment platform**, not a production financial system.

## Choose what you need

| Goal | Read | Documentation type |
| --- | --- | --- |
| Evaluate the engineering implementation | [Portfolio case study](portfolio/technical-case-study.md) | Explanation / case study |
| Understand running components | [Architecture index](architecture/README.md) | Architecture reference and explanation |
| Understand security guarantees | [Security index](security/README.md) | Security reference |
| Read design rationale | [Decision index](adr/README.md) | Architecture decisions |
| Reproduce the authenticated example | [Root quick start](../README.md#quick-start--authenticated-local-simulation) | How-to |
| Follow incremental development history | [Learning handbook](learning/README.md) | Historical tutorials and experiments |
| Understand monitoring/logging | [Observability index](observability/README.md) | Operational reference |
| Understand release/roadmap history | [Roadmap index](roadmap/README.md) | Historical plan |
| Review past release gates | [Reviews index](reviews/README.md) | Historical evidence |

## Documentation hierarchy and ownership

**Canonical current-state entry points:** root README, portfolio case study and version-qualified architecture/security indexes. **Historical evidence:** numbered learning chapters, older ADRs, prior PR reviews and recorded experiments. Historical documents must never be silently rewritten as if their original evidence was collected on the current release.

Use [documentation standards](documentation-standards.md) for classification, versioning, evidence provenance, naming, redaction, review workflow and automated checks.

### Boundaries

- The HTTP/Keycloak demo and Kafka integration/security tests are distinct verification scopes.
- No production payment processing, regulatory certification, or deployment-hardening claim is made.
- Private reconstruction lessons are planned separately; public Git history cannot be made private retroactively.
