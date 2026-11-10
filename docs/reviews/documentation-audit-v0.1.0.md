# Documentation audit — v0.1.0 baseline

**Document type:** Evidence / documentation governance  
**Status:** Open findings; migration planned, not complete  
**Audited:** 2026-11-11  
**Reference:** Released `v0.1.0` plus draft [PR #4](https://github.com/asambataiden/enterprise-payment-platform/pull/4)

## Scope and inventory

The Git tree inspected before this audit contained **82 files under `docs/learning`**, six files under `docs/architecture`, four ADR files, and separate security, observability, release review, roadmap and portfolio sections. Counts describe that inspected tree, not a guaranteed future layout.

PR #4 introduces the documentation portal, section indexes and editorial standards without physically moving historical chapters or changing the published release tag.

## Confirmed issues

| Severity | Finding | Evidence | Remediation |
| --- | --- | --- | --- |
| High | Two ADR documents identify as **ADR-0001** but describe different decisions | `docs/adr/0001-modular-monolith.md` and `docs/adr/0001-modular-monolith-and-testing-strategy.md` | Assign a unique ADR identifier to the *target microservices strategy* after full inbound-link validation; preserve decision chronology |
| High | Architecture diagrams claim old implementation boundaries | `docs/architecture.md` and `docs/architecture/system-overview.md` | Preserve as historical snapshots; refer current readers to the released portfolio case study and source |
| Medium | JWT reference route list omits later endpoints | `docs/security/jwt-resource-server.md` | Historical-status notice; canonical current API policy linked to source |
| Medium | PR #1 review says keep draft after PR was merged | `docs/reviews/pr-0001-release-readiness.md` | Historical-status notice and immutable merge/release evidence |
| Medium | Learning and performance chapters mix old proposals and verified outcomes | `docs/learning/README.md`, numbered chapters | Keep original recorded observations; classify each chapter before rewriting; do not claim unverified experiments |

## What the automation verifies

`python3 ops/docs/check-markdown-links.py` enforces relative links in canonical README/index pages and remains the **blocking CI gate**. The `--all` mode now produces a **non-blocking report** for historical Markdown pages, so debt can be measured before filenames or directories are moved.

The check does **not** currently validate: Markdown heading anchors, code-fence syntax in every page, correctness of external URLs, Mermaid rendering, command behavior, semantic contradiction or ownership claims.

## Migration strategy

1. Capture the complete `--all` report and categorize invalid targets by owner area. Do not rewrite destinations without checking whether their historical target was renamed, deleted or planned.
2. Establish unique ADR identities and update every inbound link in one docs PR; keep old decision chronology visible. Avoid changing accepted vs proposed design status without evidence.
3. Review architecture and security *current-state* references against the `v0.1.0` tag first; leave dated historical diagrams with warning banners.
4. Classify the learning archive into **tutorials**, **how-to guides**, **reference**, **explanations**, **benchmarks/evidence**, and **incident investigations**. Migrate only after link checks and provenance review.
5. Author new detailed, automated, tested reconstruction lessons in a separately created private repository. Public Git history remains publicly accessible even after deletion or relocation.
6. Expand CI gates incrementally only after the legacy baseline is repaired, so a documentation migration never silently discards experiment evidence.

## Release safety

All changes in PR #4 are documentation, documentation validation scripts and workflow configuration. They do not change payment-processing runtime behavior or the existing `v0.1.0` tag. Any future merge remains subject to passing CI and owner review.
