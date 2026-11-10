# Learning Archive — Navigation Catalog

> **Historical archive, not a current implementation contract.** The entries below are classified by **chronological chapter number**, not by definitive Diátaxis type. Each chapter preserves its original incremental scope, test environment and potential “pending” statements. Use the [current technical case study](../portfolio/technical-case-study.md) for release-verified claims.

**Index baseline:** Git tree on documentation PR #4; 79 numbered chapters found. This catalog is a navigational aid, not a claim that all chapters have been audited for technical accuracy.

**Start here:** [beginner playbook](00-start-from-zero-beginner-playbook.md) · [guided labs](guided-labs.md) · [interview workbook](interview-workbook.md) · [learning README](README.md).

## 01 — Foundations and architecture (00–07)

- [00 start from zero beginner playbook](00-start-from-zero-beginner-playbook.md)
- [01 problem domain and modular monolith](01-problem-domain-and-modular-monolith.md)
- [02 http contracts and application boundaries](02-http-contracts-and-application-boundaries.md)
- [03 postgresql flyway money and idempotency](03-postgresql-flyway-money-and-idempotency.md)
- [04 jwt merchant security and trust boundaries](04-jwt-merchant-security-and-trust-boundaries.md)
- [05 quality gates observability and devsecops](05-quality-gates-observability-and-devsecops.md)
- [06 ledger atomic transitions and outbox fundamentals](06-ledger-atomic-transitions-and-outbox-fundamentals.md)
- [07 architecture quality and test reuse](07-architecture-quality-and-test-reuse.md)

## 02 — Kafka contracts, security, recovery and operations (08–37)

- [08 kafka consumer trust and inbox](08-kafka-consumer-trust-and-inbox.md)
- [09 kafka consumer offsets and recovery](09-kafka-consumer-offsets-and-recovery.md)
- [10 opt in kafka listener and projection](10-opt-in-kafka-listener-and-projection.md)
- [11 kafka poison evidence and operator recovery](11-kafka-poison-evidence-and-operator-recovery.md)
- [12 kafka container lifecycle and metrics](12-kafka-container-lifecycle-and-metrics.md)
- [13 package refactor error contracts openapi](13-package-refactor-error-contracts-openapi.md)
- [14 openapi compatibility gate](14-openapi-compatibility-gate.md)
- [15 kafka event contract governance](15-kafka-event-contract-governance.md)
- [16 kafka consumer contract compatibility](16-kafka-consumer-contract-compatibility.md)
- [17 kafka strict json parsing](17-kafka-strict-json-parsing.md)
- [18 jackson dependency governance](18-jackson-dependency-governance.md)
- [19 merchant identifier contract consistency](19-merchant-identifier-contract-consistency.md)
- [20 kafka canonical uuid validation](20-kafka-canonical-uuid-validation.md)
- [21 merchant identifier contract policy](21-merchant-identifier-contract-policy.md)
- [22 executable json schema contract verification](22-executable-json-schema-contract-verification.md)
- [23 fail closed kafka offset configuration](23-fail-closed-kafka-offset-configuration.md)
- [24 kafka ack failure idempotent recovery](24-kafka-ack-failure-idempotent-recovery.md)
- [25 kafka consumer recovery assessment](25-kafka-consumer-recovery-assessment.md)
- [26 kafka listener lifecycle observability](26-kafka-listener-lifecycle-observability.md)
- [27 kafka consumer lag and alerting design](27-kafka-consumer-lag-and-alerting-design.md)
- [28 kafka lag monitoring cached metrics](28-kafka-lag-monitoring-cached-metrics.md)
- [29 secure prometheus export and alerting](29-secure-prometheus-export-and-alerting.md)
- [30 kafka real recovery drill](30-kafka-real-recovery-drill.md)
- [31 kafka deployment transport security](31-kafka-deployment-transport-security.md)
- [32 secured kafka broker e2e contract](32-secured-kafka-broker-e2e-contract.md)
- [33 disposable mtls kafka security lab](33-disposable-mtls-kafka-security-lab.md)
- [34 kafka negative security tests](34-kafka-negative-security-tests.md)
- [35 execution evidence and retrospective runbook](35-execution-evidence-and-retrospective-runbook.md)
- [36 kafka producer consumer acls](36-kafka-producer-consumer-acls.md)
- [37 kafka idempotent producer authorization](37-kafka-idempotent-producer-authorization.md)

## 03 — Payment history, API contracts and simulation guardrails (38–42)

- [38 merchant payment history and auditability](38-merchant-payment-history-and-auditability.md)
- [39 merchant payment search and keyset pagination](39-merchant-payment-search-and-keyset-pagination.md)
- [40 api error handling resilience foundation](40-api-error-handling-resilience-foundation.md)
- [41 sensitive data safe error logging](41-sensitive-data-safe-error-logging.md)
- [42 fail closed simulation mode](42-fail-closed-simulation-mode.md)

## 04 — PostgreSQL and connection-pool measurement (43–48)

- [43 postgresql query performance and resilience](43-postgresql-query-performance-and-resilience.md)
- [44 reproducible postgresql performance benchmark](44-reproducible-postgresql-performance-benchmark.md)
- [45 postgresql benchmark evidence and findings](45-postgresql-benchmark-evidence-and-findings.md)
- [46 statistical postgresql read write benchmark](46-statistical-postgresql-read-write-benchmark.md)
- [47 statistical postgresql benchmark results](47-statistical-postgresql-benchmark-results.md)
- [48 hikari pool saturation and recovery](48-hikari-pool-saturation-and-recovery.md)

## 05 — HTTP load and multi-tenant performance laboratories (49–56; 61–64)

- [49 concurrent authenticated http load lab](49-concurrent-authenticated-http-load-lab.md)
- [50 concurrent http load evidence and decision](50-concurrent-http-load-evidence-and-decision.md)
- [51 bounded mixed http load and db telemetry](51-bounded-mixed-http-load-and-db-telemetry.md)
- [52 mixed http workload results and baseline](52-mixed-http-workload-results-and-baseline.md)
- [53 controlled http rate ladder](53-controlled-http-rate-ladder.md)
- [54 http rate ladder results and decision](54-http-rate-ladder-results-and-decision.md)
- [55 multi tenant repeatability performance lab](55-multi-tenant-repeatability-performance-lab.md)
- [56 multi tenant repeatability results](56-multi-tenant-repeatability-results.md)
- [61 tenant fairness and windowed latency from zero](61-tenant-fairness-and-windowed-latency-from-zero.md)
- [62 verified tenant fairness and windowed latency results](62-verified-tenant-fairness-and-windowed-latency-results.md)
- [63 repeated tenant fairness lab from zero](63-repeated-tenant-fairness-lab-from-zero.md)
- [64 repeated 100rps tenant performance verified results](64-repeated-100rps-tenant-performance-verified-results.md)

## 06 — Verified results, investigations, JVM diagnostics and evidence governance (57–60; 65–78)

- [57 idempotency under concurrent http retries from zero](57-idempotency-under-concurrent-http-retries-from-zero.md)
- [58 concurrent idempotency http local test evidence](58-concurrent-idempotency-http-local-test-evidence.md)
- [59 sustained mixed http workload from zero](59-sustained-mixed-http-workload-from-zero.md)
- [60 sustained http load results and explanation](60-sustained-http-load-results-and-explanation.md)
- [65 large corpus raw latency evidence from zero](65-large-corpus-raw-latency-evidence-from-zero.md)
- [66 large corpus results and latency spike investigation](66-large-corpus-results-and-latency-spike-investigation.md)
- [67 raw request clusters latency incident evidence](67-raw-request-clusters-latency-incident-evidence.md)
- [68 seven latency clusters and diagnostic hypotheses](68-seven-latency-clusters-and-diagnostic-hypotheses.md)
- [69 correlated jvm client timing diagnostics from zero](69-correlated-jvm-client-timing-diagnostics-from-zero.md)
- [70 missing jvm log diagnostic recovery](70-missing-jvm-log-diagnostic-recovery.md)
- [71 surefire jvm pid logging recovery from zero](71-surefire-jvm-pid-logging-recovery-from-zero.md)
- [72 verified jvm diagnostics and phase aware analysis](72-verified-jvm-diagnostics-and-phase-aware-analysis.md)
- [73 verified g1 pause timeline and nonrecurring latency spikes](73-verified-g1-pause-timeline-and-nonrecurring-latency-spikes.md)
- [74 g1 pause parser regression and verified evidence](74-g1-pause-parser-regression-and-verified-evidence.md)
- [75 request level client server timing from zero](75-request-level-client-server-timing-from-zero.md)
- [76 independent client process architecture from zero](76-independent-client-process-architecture-from-zero.md)
- [77 performance evidence governance from zero](77-performance-evidence-governance-from-zero.md)
- [78 benchmark evidence ci integration from zero](78-benchmark-evidence-ci-integration-from-zero.md)

## Chapter review checklist

Before promoting a historical page into the **private reconstruction curriculum**, verify: original SHA and tested version; real commands and expected outcomes; diagram boundaries; failure and rollback behavior; reproducible, idempotent automation; secret redaction; CI versus opt-in lab scope; and interview explanation. **Do not copy public history into a new private repository as a claim of confidentiality.**
