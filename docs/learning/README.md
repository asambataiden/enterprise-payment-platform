# Enterprise Payment Platform — Learning and Interview Handbook

> **Start here.** Learn the architecture from the original problem to the current Kafka outbox integration. This is an **educational simulated payments platform**, not a regulated payments implementation. Read the **implemented / planned** distinction on every page.

## Complete beginner? Start here first

Read the [start-from-zero beginner playbook](00-start-from-zero-beginner-playbook.md)
before any numbered milestone. It explains the business problem, every
basic vocabulary term, the real request path, safe setup on macOS,
a first end-to-end use case, a HikariCP hands-on lesson and a
repeatable teaching/evidence checklist. Our documentation standard
is now **plain-language explanation first, precise engineering
explanation second**, from prerequisites to tests and limitations.

## Milestone 18 — Follow the implementation line by line

[Chapter 57: concurrent signed-token idempotency, explained from zero](57-idempotency-under-concurrent-http-retries-from-zero.md)
explains each test stage, its SQL evidence, expected HTTP results,
terminal commands and the difference between correctness and load
testing. Read it after the [beginner playbook](00-start-from-zero-beginner-playbook.md).

## Milestone 18 Part B — Sustained load, starting from first principles

[Chapter 59: controlled 30/60-second sustained HTTP workload](59-sustained-mixed-http-workload-from-zero.md)
documents the actual implementation, why the new duration property
is bounded, the disposable environment, runtime commands, metrics
and their limitations, troubleshooting, examples and interview
questions. The test remains opt-in; **no measured results exist
until a local run is supplied**.

## Milestone 19 — Verified local evidence

[Chapter 62: actual per-merchant and 10-second-window results](62-verified-tenant-fairness-and-windowed-latency-results.md)
records the successful corrected workload at tested commit
`217a8c87`, the two passing tenant distribution unit tests,
3,750 successful measured HTTP calls, per-tenant and temporal p95/p99
figures, Hikari/JVM observations and interpretation limits.
This evidence is specific to the developer's local experiment.

## Milestone 19 — Ten-second trends and tenant fairness

[Chapter 61: beginner tutorial on time-windowed latency and tenant fairness](61-tenant-fairness-and-windowed-latency-from-zero.md)
documents a discovered correlated-selector defect, its deterministic
regression test, separated GET/POST per-tenant metrics and the
exact opt-in local experiment. Previous results must not be
interpreted as measuring per-tenant write fairness.

## Milestone 20 — Verified repeated-trial evidence

[Chapter 64: two independent 100-RPS trials, actual results](64-repeated-100rps-tenant-performance-verified-results.md)
records the developer's verified **12,000 successful measured requests**,
read/write latency by merchant and ten-second window, JVM and
Hikari/PostgreSQL observations, limitations, CI evidence, and
beginner/interview explanations. Tested implementation SHA:
`b7362f1025af49f6789d83d75c93c58ceeb76486`.

## Milestone 20 — Repeatability and tenant completion rates

[Chapter 63: two independent longer tenant-fairness trials](63-repeated-tenant-fairness-lab-from-zero.md)
teaches the why, what, how, commands, measurements, limitations
and interview defense. The runner is opt-in and its performance
results cannot be claimed before the developer's local run.

## Milestone 21 — Seven cluster findings and diagnostic next steps

[Chapter 68: seven validated >50-ms request clusters](68-seven-latency-clusters-and-diagnostic-hypotheses.md)
records all sequence ranges, their nominal start offsets,
affected merchants, maximum latencies, the approximately
1.5-second spacing clue, CI evidence, and what is still
unknown. We should next correlate actual request timings
with JVM and host events before changing production-style
configuration.

## Milestone 21 — Raw latency incident follow-up

[Chapter 67: validated CSV findings, slow-request counts and sequence-level diagnosis](67-raw-request-clusters-latency-incident-evidence.md)
records the 62 requests over 20 ms, 37 over 50 ms and 16 over 100 ms,
all over-50-ms observations being concentrated in the 30–40-second
scheduled window. The offline analyzer now includes *heuristic*
sequence-cluster summaries; no JVM, host or DB root cause has
yet been established.

## Milestone 21 — Verified large-corpus results and incident investigation

[Chapter 66: actual 6,000-request results and a transient tail-latency spike](66-large-corpus-results-and-latency-spike-investigation.md)
records the successful 128-records-per-merchant test at
`e3c2ef2b` and explains the **30–40s p95/p99 spike**.
The [offline raw CSV analyzer](../../ops/benchmarks/http/analyze-raw-latency.py)
validates all 6,000 rows and identifies slow request clusters
without uploading test data or using network access. The
incident's root cause remains **unverified**.

## Milestone 21 — Larger corpus and raw request-level evidence

[Chapter 65: beginner tutorial for larger synthetic datasets and raw CSV timings](65-large-corpus-raw-latency-evidence-from-zero.md)
documents the bounded 128-records-per-merchant experiment, evidence
columns, CSV integrity checks, command-by-command walkthrough,
tradeoffs, limits and interview questions. The experiment is opt-in
and has not been executed locally for this commit.

## Milestone 22 — Verified JVM evidence and phase-aware interpretation

[Chapter 72: successfully captured forked JVM evidence and distinguished startup from measured events](72-verified-jvm-diagnostics-and-phase-aware-analysis.md)
records the 6,000-request local success at tested SHA `701dc6e4`,
validated test PID and all four evidence files, and the absence
of >50-ms requests in that run. The enhanced analyzer reports
startup, measured-stage and post-stage JVM log-line counts
without interpreting a partial printed excerpt as complete evidence.

## Milestone 22 — Identify the test JVM, not Maven

[Chapter 71: per-process GC/safepoint evidence recovery from zero](71-surefire-jvm-pid-logging-recovery-from-zero.md)
documents the second successful 6,000-request HTTP run whose JVM
log was still missing, and explains the new `JAVA_TOOL_OPTIONS`
logging scheme with `%p` process separation, the actual JUnit
PID marker, preflight cleanup and independent evidence validation.
The logging fix remains pending local verification.

## Milestone 22 — Evidence pipeline failure and recovery

[Chapter 70: JVM log missing after a successful HTTP load test](70-missing-jvm-log-diagnostic-recovery.md)
records 6,000 successful measured requests and the distinct failure
to collect a GC/safepoint log. It explains the corrected space-free
absolute JVM logging path, preflight cleanup, evidence integrity checks,
and the local rerun. The underlying latency anomaly remains open.

## Milestone 22 — Client request timelines and JVM evidence

[Chapter 69: correlated client offsets and GC/safepoint logging explained from zero](69-correlated-jvm-client-timing-diagnostics-from-zero.md)
documents implemented bounded diagnostic instrumentation, the
offline analyzer, Mac commands, limitations and interview questions.
It does not claim the prior latency anomaly was caused by GC
or that the new run has been executed.

## Learning contract

For practical commands use a discovered repository root and preserve **observed** test evidence. Chapter 35 maintains an execution timeline and a retrospective documentation backlog for Chapters 01–34; do not treat an implementation commit as proof of runtime verification.

Each milestone must answer **WHAT**, **WHY**, **HOW**, **ALTERNATIVES**, **FAILURE MODES**, **PROOF**, **INDUSTRY CONTEXT**, and **HOW TO EXPLAIN IT IN AN INTERVIEW**. Every meaningful change to implementation or infrastructure must update the relevant architecture, scenario, ADR, evidence and operations documentation **in the same PR**. This documentation is versioned alongside the code.

### Foundational chapters (01–06)

These chapters reconstruct the originally implemented foundations. They complement
the guided labs rather than replacing architectural records or tests.

1. [01 — Problem domain and modular monolith](01-problem-domain-and-modular-monolith.md)
2. [02 — HTTP contracts and application boundaries](02-http-contracts-and-application-boundaries.md)
3. [03 — PostgreSQL, Flyway, money and idempotency](03-postgresql-flyway-money-and-idempotency.md)
4. [04 — JWT merchant security and trust boundaries](04-jwt-merchant-security-and-trust-boundaries.md)
5. [05 — Quality gates, observability and DevSecOps](05-quality-gates-observability-and-devsecops.md)
6. [06 — Ledger, atomic transitions and outbox foundations](06-ledger-atomic-transitions-and-outbox-fundamentals.md)

### Learning sequence (follow in order)

1. [Guided labs: from zero through Kafka](guided-labs.md) — hands-on reading, exercises, expected boundaries and interview checkpoints.
2. [Capstone roadmap, from project inception](../roadmap/enterprise-payment-platform.md) — scope, increments, decisions, status, later phases.
3. [Bird's-eye architecture and modules](../architecture/system-overview.md) — C4-like context, container and component views, boundaries and full data flow.
4. [Use cases, runtime and sequence diagrams](../architecture/runtime-scenarios.md) — intake, concurrent retry, capture, Kafka delivery, failure and recovery.
5. [Reusable code and module strategy](../architecture/modularity-and-reuse.md) — how to avoid repeated logic and when **not** to extract another library.
6. Existing design records: [ADR-0001](../adr/0001-modular-monolith.md), [ADR-0002](../adr/0002-money-and-idempotency.md), [ADR-0003](../adr/0003-simulated-ledger-and-payment-state-machine.md).
7. Existing focused engineering contracts: [JWT trust](../security/jwt-resource-server.md), [merchant isolation](../security/merchant-isolation.md), [outbox delivery](../architecture/outbox-delivery-contract.md), [Kafka contract](../architecture/kafka-integration-contract.md), [PR #1 review](../reviews/pr-0001-release-readiness.md).
8. [Architecture quality and test reuse](07-architecture-quality-and-test-reuse.md) — practical refactor, dependency rules and tradeoffs.
9. [Kafka consumer trust and inbox](08-kafka-consumer-trust-and-inbox.md) — real-broker contract checks, replays and trust boundaries.
10. [Kafka offsets, fail-stop recovery and polling](09-kafka-consumer-offsets-and-recovery.md) — why commit after the DB transaction, how restarts replay.
11. [Opt-in Kafka listener and typed projection](10-opt-in-kafka-listener-and-projection.md) — manual ACK, fail-stop and typed local database effects.
12. [Kafka poison evidence and recovery](11-kafka-poison-evidence-and-operator-recovery.md) — persistent observations, operator runbook and no-skip policy.
13. [Real Kafka container lifecycle and metrics](12-kafka-container-lifecycle-and-metrics.md) — successful/poison startup, stop, counters and evidence limits.
14. [Package boundaries, API error contracts and OpenAPI](13-package-refactor-error-contracts-openapi.md) — practical refactoring, schema stability and security.
15. [OpenAPI contract compatibility gate](14-openapi-compatibility-gate.md) — reviewed semantic baseline, CI failure and migration decisions.
16. [Interview workbook](interview-workbook.md) — technical questions, tradeoffs and guided explanations.

## Repeatable learning method

For each milestone:

1. Describe the **problem** without naming a technology.
2. State the **invariant** and identify where it must be enforced (client, domain, DB, broker, deployment).
3. Sketch a sequence diagram; identify trust boundaries and failure points.
4. Read the relevant classes and SQL migration **in call order**, not alphabetically.
5. Run the unit tests, focused integration tests and `mvn -B clean verify`; observe actual behavior, not just green status.
6. Deliberately predict a failure (e.g. duplicate idempotency key, wrong tenant, expired outbox lease) and find the test that proves the expected response.
7. Explain why the selected design is **appropriate for this scale and requirements**, compare one plausible alternative, and state its limitations.
8. Update learning notes, ADR if the decision changes, diagram and CI evidence. Record the exact commit for the evidence.

### Milestone page template

Copy this structure into milestone-specific notes or PR descriptions; **do not reproduce entire class definitions**:

- **Problem / actor / business outcome**
- **Functional and non-functional requirements**, assumptions and out-of-scope
- **Architecture fit**: module, interfaces, trust and transaction boundaries
- **What was built**: links to source files, tests, schema, CI
- **Why this solution**: alternatives and explicit tradeoffs
- **Walkthrough**: step-by-step request / event sequence
- **Failure and abuse cases**: expected status, retry, atomicity and telemetry
- **Best-practice context**: practice, conditions, limits, reference
- **Operational consequences**: deployment, configuration, monitoring, backup, secrets
- **Interview rehearsal**: 30-second pitch, 2-minute deep dive, follow-up questions
- **Status and evidence**: implemented/tested vs proposed, commit SHA and dated results

## What 'industrial best practice' means here

There is no single universal best architecture. Record the **requirements, constraints and quality attributes** that justify each decision. Prefer well-known approaches such as domain/module boundaries, secure defaults, database constraints, transactional outbox, idempotent consumers, immutable migration history, automated tests, observability and security gates **when they solve demonstrated risks**. Avoid untested claims of exactly-once delivery or compliance.

We use a pragmatic combination of [C4's zoom levels](https://c4model.com/introduction) and [arc42's architecture concerns](https://arc42.org/overview/) as navigation conventions, not as an excuse to write repetitive documents. The [transactional outbox](https://microservices.io/patterns/data/transactional-outbox) is the external reference for message publication reliability; our exact guarantees are established by **our own tests**.

### Recent focused chapters (15–20)

- [15 — Kafka producer contract governance](15-kafka-event-contract-governance.md)
- [16 — Kafka consumer contract compatibility](16-kafka-consumer-contract-compatibility.md)
- [17 — Strict Kafka JSON parsing](17-kafka-strict-json-parsing.md)
- [18 — Jackson dependency governance](18-jackson-dependency-governance.md)
- [19 — Merchant identifier object invariants](19-merchant-identifier-contract-consistency.md)
- [20 — Canonical Kafka UUID validation](20-kafka-canonical-uuid-validation.md)
- [21 — Merchant identifier contract policy and Unicode compatibility](21-merchant-identifier-contract-policy.md)
- [22 — Executable Draft 2020-12 JSON Schema verification](22-executable-json-schema-contract-verification.md)
- [23 — Fail-closed Kafka offset configuration](23-fail-closed-kafka-offset-configuration.md)
- [24 — Kafka ACK failure and idempotent recovery](24-kafka-ack-failure-idempotent-recovery.md)
- [25 — Kafka consumer recovery assessment and operator triage](25-kafka-consumer-recovery-assessment.md)
- [26 — Kafka listener lifecycle observability](26-kafka-listener-lifecycle-observability.md)
- [27 — Kafka consumer lag assessment and alerting design](27-kafka-consumer-lag-and-alerting-design.md)
- [28 — Cached Kafka lag monitoring and operational metrics](28-kafka-lag-monitoring-cached-metrics.md)
- [29 — Secure Prometheus export and alert integration](29-secure-prometheus-export-and-alerting.md)
- [30 — Real Kafka transient-failure and controlled recovery drill](30-kafka-real-recovery-drill.md)
- [31 — Kafka deployment transport security and least privilege](31-kafka-deployment-transport-security.md)
- [32 — Secured Kafka broker integration and ACL-denial contract](32-secured-kafka-broker-e2e-contract.md)
- [33 — Disposable mTLS Kafka security lab and ACL verification](33-disposable-mtls-kafka-security-lab.md)
- [34 — Kafka negative mTLS and authorization security tests](34-kafka-negative-security-tests.md)
- [35 — Execution evidence, operator errors and retrospective learning](35-execution-evidence-and-retrospective-runbook.md)
- [36 — Kafka producer and consumer-group least-privilege ACLs](36-kafka-producer-consumer-acls.md)
- [37 — Kafka idempotent producer authorization and cluster ACLs](37-kafka-idempotent-producer-authorization.md)

The foundational chapters are retrospective teaching material. Recheck the current
repository implementation and tests before asserting any behavior as verified.

## Historical verification evidence

On `feat/payment-ledger`, GitHub Java CI #179 and Security CI #93 passed at implementation commit `98ace4d`: **89 tests** (46 fast, 43 integration). Additional documentation-only commits are **not covered by that historical SHA**; always inspect checks on the current head. That 89-test count is historical, not the current baseline. Subsequent changes introduced an opt-in Kafka consumer boundary and additional tests. Consult current GitHub checks and local Maven reports for up-to-date implementation evidence; there is still no real funds movement.
