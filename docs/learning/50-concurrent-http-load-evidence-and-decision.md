# Chapter 50 — Concurrent HTTP Load: Local Execution Evidence and Decision

## Provenance

- Tested revision: `7a393693d1f9bbe87d37568f9704e774ef08e2f2`
- Execution: November 10, 2026, on the developer's Mac, local loopback HTTP
- PostgreSQL: disposable `postgres:17-alpine` Testcontainers database
- Runtime: Java 25, Spring Boot 4.1.2, signed test-only JWTs
- Lab configuration: Hikari maximum pool size 2, acquisition timeout 500 ms
- Harness: `ops/benchmarks/http/run-local.sh`, `ConcurrentHttpLoadLab`
- Local results were supplied from the test run log; no external API
  requests or real payment transactions were involved.

## Observed three-phase results

| Phase | Concurrency | Requests | HTTP 200 | HTTP 503 | Other | p50 ms | p95 ms | p99 ms | Observed short-burst RPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Warm-up | 2 | 8 | 8 | 0 | 0 | 4.631 | 93.199 | 93.199 | 71.41 |
| Forced saturation | 12 | 24 | 0 | 24 | 0 | 510.087 | 517.974 | 517.990 | 23.35 |
| Recovery | 8 | 40 | 40 | 0 | 0 | 5.686 | 11.194 | 13.329 | 1128.70 |

**BUILD SUCCESS**; the opt-in test executed one JUnit test with zero
failures, errors or skips. These are client-observed wall-clock values,
not latency percentiles from a long-running steady-state load test.

## Explanation

Warm-up starts a real local HTTP server, security filter chain, and
connection-backed service. Its p95 is impacted by cold HTTP and JVM
initialization, and its eight requests are too few for meaningful
tail-latency inference.

During forced saturation, two JDBC leases are held for the phase.
Hikari logs confirm `total=2, active=2, idle=0` and connection
acquisition timeouts around 501–505 ms. All 24 HTTP calls correctly
resulted in 503 errors, with no 500, 401 or 403 responses.

After releasing both JDBC leases, all 40 read requests completed with
HTTP 200. This supports the bounded failure and recovery behavior of
this *specific* synthetic request path and pool configuration.

## Security and reliability interpretation

- Valid signed test JWTs were accepted by the real security chain.
- No application debug token or signing key was included in evidence.
- The application mapped database acquisition failures into
  `SERVICE_UNAVAILABLE` without showing the connection exception
  message in its public response.
- Hibernate/Hikari logged acquisition timeout details under expected
  deliberate exhaustion. This local diagnostic behavior is distinct
  from the sanitized application error boundary; operational log
  redaction policies still require review in deployed environments.
- All phases used **read-only GET** requests. This does not validate
  idempotent payment creation under write contention.

## Engineering decision

**GO — close the Milestone 14 controlled HTTP saturation-and-recovery
lab.** Tested commit `7a393693` also passed:

- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42780941190)
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42780941222)

Do not extrapolate 1,128.70 short-burst recovery RPS to sustainable
capacity, cloud deployment performance, or a production SLO. The test
does not measure real concurrency distributions, TLS gateway overhead,
multi-replica behavior, write throughput, CPU saturation or
PostgreSQL lock waits.

## Next experiment

A separately opt-in steady-state test should use longer measured
windows, a bounded workload generator, read/write mix and tenant skew.
Capture Hikari active/idle/pending connections, DB waits, HTTP
latency/error distributions, rate and capacity limits, with hard
safety cutoffs. Never target production or use real funds.
