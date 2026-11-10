# Chapter 52 — Mixed Authenticated HTTP Workload: Results and Baseline

## Execution evidence

The developer ran the opt-in `MixedHttpWorkloadLab` locally on
**2026-11-10 at 08:37 UTC** (09:37 Europe/Berlin).
Test revision: `9954b223154acb63dff592f18b7788ca3fd23181`.
The runner reported **BUILD SUCCESS**, 1 executed test, no failures,
errors or skips. It used Java 25, an ephemeral signed JWT, Spring Boot
on loopback and disposable PostgreSQL 17 via Testcontainers. Database
Flyway migrations 1–9 applied successfully.

The tested revision also passed:
- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42783782860)
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42783783004)

Normal CI compiles this opt-in laboratory but does not run its workload.
The following figures come exclusively from the supplied local log.

## Traffic and outcomes

Offered schedule: 300 requests at 40 ms intervals (nominally 25/s),
8 worker threads and one synthetic merchant, with an 80/20 read/write
mix. All 20 warm-up requests also succeeded.

| Phase | Operation | Requests | Success | Unavailable | Unexpected | p50 ms | p95 ms | p99 ms |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Warm-up | GET read | 16 | 16 | 0 | 0 | 4.042 | 77.535 | 77.535 |
| Warm-up | POST create | 4 | 4 | 0 | 0 | 5.366 | 26.784 | 26.784 |
| Measured | GET read | 240 | 240 | 0 | 0 | 5.644 | 7.789 | 9.133 |
| Measured | POST create | 60 | 60 | 0 | 0 | 7.677 | 10.330 | 11.258 |

Measured phase lasted 12.004 seconds, with **24.99 completed
requests/second** and zero unexpected HTTP statuses. A unique
idempotency key was supplied for each simulated-payment create
request. The test does not examine repeated-key retry correctness
under concurrent duplicate writes.

## Instrumentation

HikariCP configured maximum: **4 connections**.

| Telemetry | Value |
| --- | ---: |
| Samples collected | 48 |
| Maximum observed active Hikari connections | 1 |
| Maximum observed pending Hikari acquisitions | 0 |
| Maximum observed active PostgreSQL sessions with wait event | 0 |
| Metrics sampler failures | 0 |

This workload **did not saturate** the application connection pool;
the observed maximum of one active connection indicates very low
pool occupancy at the sampling instants. The database wait counter is
a sampled, filtered `pg_stat_activity` observation and can miss
brief waits; it is not proof of zero contention at all instants.
The separate DB monitor does not consume a Hikari pool connection.

## Engineering decision

**GO — close the 25-RPS mixed workload functional/performance
baseline.** All 300 authenticated read/write HTTP calls succeeded,
and sampled pool monitoring found no acquisition queue or active DB
waits during this run. Retain the current configured four-connection
**test** pool for this lab.

**Do not** extrapolate these loopback figures to production p95/p99
SLOs, maximum sustainable throughput, server-side compute capacity,
multi-replica pool sizing, or payment/settlement functionality.
The samples are relatively small, from one host and one run, with
identical read targets and a uniform tenant, no gateway or TLS
termination and no background business traffic.

## Next test: evidence-based load progression

Advance through **separate rate-limited scenarios** rather than
arbitrarily increasing `DB_POOL_MAX_SIZE`: e.g. 25, 50 and 100
requests per second with the same 80/20 mix, bounded duration,
multiple runs and hard stop conditions. Capture actual delivered
arrival rate, queued requests, active/pending Hikari connections,
error counts, GC, CPU, DB waits and SQL times. Model multiple
tenants and realistic different read targets; verify idempotency
with concurrent retries in a distinct correctness test.

Never direct load traffic to a live customer or financial system.
