# Documentation engineering standard

**Status:** Current editorial policy (documentation-only; does not change runtime behavior).  
**Baseline:** v0.1.0 source tag, plus subsequent documentation-only changes on `main`.

## Audience and information architecture

Use the [Diátaxis model](https://diataxis.fr/) when introducing documentation:

1. **Tutorial:** teach by doing in a safe, reproducible lab; intended for the private reconstruction repository for new extended courses.
2. **How-to:** solve a specific task with prerequisites, commands, validation, cleanup and rollback.
3. **Reference:** state exact interfaces, configuration, schema, invariants, versions and defaults.
4. **Explanation:** describe why and how decisions affect quality, security, operability and tradeoffs.

Organize architectural narratives using compact [arc42](https://arc42.org/overview/) views: context, building blocks, runtime, deployment, decisions, quality requirements and risks. Do not invent deployment artifacts. Apply [MADR](https://adr.github.io/madr/) principles for durable architecture decisions.

## Required page metadata

Every **new or substantively revised** technical page should state, when applicable:

- **Type:** tutorial, how-to, reference, explanation, ADR, evidence, or historical record.
- **Status:** current, historical, planned, experimental or superseded.
- **Verified against:** tag/SHA and relevant runtime/test context.
- **Scope:** implemented, opt-in, test-only, locally demonstrated, or proposed.
- **Owner:** responsible role or team (do not invent individuals).
- **Last reviewed:** ISO date; not necessarily the date the original experiment occurred.

Retain original results and observation timestamps; add a clearly dated correction note when facts change. Avoid ambiguous phrases like “current”, “latest”, “passed”, or “production-ready” without a verified baseline.

## Engineering evidence standard

An evidence claim should include: exact command, git SHA, environment dependencies, expected and observed outcome, test/CI link or sanitized artifact location, failures/skips, and limitations. Keep opt-in benchmark evidence separate from regular CI. **Do not infer performance or causal diagnoses from tests that were not run.**

Never commit passwords, tokens, client secrets, credentials, private keys, real customer data, raw sensitive traces, unsanitized broker configurations, or generated certificate material. Redact data before documenting an incident.

## File and link conventions

- Lowercase kebab-case filenames; use `README.md` as each populated directory's entry point.
- Use relative Markdown links for in-repository material and version-pinned GitHub links for immutable release evidence.
- Use fenced `mermaid` diagrams with as-built and optional/future connections clearly distinguished.
- Number ADRs uniquely across the repository; use explicit Accepted / Proposed / Superseded status and links when replacing decisions.
- Group tutorials by learning objective, not by tool popularity; distinguish experiments, observations, and architecture explanations.
- Put idempotent and non-destructive repeatable actions into scripts; show validation, security boundaries and cleanup. Destructive actions require an explicit opt-in.

## Change and review gate

For each docs PR: link the relevant implementation/tests, update the entry point, run relative-link checking and CI, and confirm no misleading claim of a financial/compliance or production guarantee. Changes to code, operational behavior or architecture should update matching documentation in the **same PR**.

**Migration policy:** Do not bulk-move `docs/learning` or old ADRs in a docs-only cleanup. Those paths are deeply cross-linked, and the public history is immutable. Deprecate inaccurate *current-state* entry points and migrate historical chapters only with link validation and separate review.
