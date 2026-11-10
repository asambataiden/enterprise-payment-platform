# Observability documentation

**Status:** development baseline / selective opt-in features; no production observability platform demonstrated.

- [Structured logging and correlation IDs](logging.md) — ECS JSON logs and privacy constraints.
- [Kafka listener lifecycle and diagnostics](../learning/26-kafka-listener-lifecycle-observability.md) — incremental implementation chapter.
- [Kafka lag monitoring](../learning/28-kafka-lag-monitoring-cached-metrics.md) — selected instrumentation and limitations.
- [Metrics endpoint hardening](../learning/29-secure-prometheus-export-and-alerting.md) — scoped access and negative verification.
- [Performance evidence governance](../learning/77-performance-evidence-governance-from-zero.md) — synthetic evidence provenance.

**Operational caveat:** existing counters/logs and isolated diagnostics are not evidence of configured external alert routing, SLO enforcement, long-term telemetry retention or on-call operations. Never export tokens, credentials or sensitive merchant data in logs/metrics.
