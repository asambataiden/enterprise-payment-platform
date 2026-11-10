# Chapter 54 — Controlled HTTP Rate Ladder: Execution Evidence and Decision

## Provenance

The developer executed `bash ops/benchmarks/http/run-rate-ladder.sh`
on **2026-11-10**, starting **08:50:45 UTC**, using commit
`51d35a1aa00044099f5450af12141b8bdbaff7f5`.
The run completed **25 → 50 → 100 requests/second** in three
independent opt-in Testcontainers PostgreSQL 17 / localhost Spring
Boot environments. Each stage returned `BUILD SUCCESS` (one JUnit
test, zero failures/errors/skips).

The implementation commit passed [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42784999323)
and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42784998911).
These workflows compile/test the normal suite but do not run
the opt-in performance ladder. The numbers below originate exclusively
from the developer's supplied local console output.

## Measured outcomes

Each step used 8 workers, maximum 32 outstanding client requests,
4 maximum Hikari connections, 20 warm-up requests, a 12-second
dispatch window and a deterministic **80% GET / 20% POST** mix.
Every GET read the same synthetic merchant-owned payment; every POST
created a distinct simulated payment with a unique idempotency key.
No transitions, settlement or external payments were involved.

| Metric | 25 RPS | 50 RPS | 100 RPS |
| --- | ---: | ---: | ---: |
| Measured HTTP requests | 300 | 600 | 1200 |
| Successful GET (200) | 240 | 480 | 960 |
| Successful POST (201) | 60 | 120 | 240 |
| Unavailable (503) | 0 | 0 | 0 |
| Unexpected statuses | 0 | 0 | 0 |
| Achieved completed RPS | 25.05 | 50.05 | 100.03 |
| Measured elapsed seconds | 11.974 | 11.989 | 11.996 |
| GET p50, ms | 5.280 | 4.228 | 3.153 |
| GET p95, ms | 8.025 | 6.304 | 5.183 |
| GET p99, ms | 10.314 | 7.678 | 6.444 |
| POST p50, ms | 7.036 | 6.448 | 4.687 |
| POST p95, ms | 11.259 | 9.064 | 6.798 |
| POST p99, ms | 12.691 | 10.068 | 8.318 |
| Max sampled active Hikari connections | 1 | 1 | 1 |
| Max sampled pending Hikari connections | 0 | 0 | 0 |
| Max sampled active PostgreSQL waits | 0 | 0 | 0 |
| Pool/DB samples | 48 | 48 | 48 |
| Monitoring failures | 0 | 0 | 0 |
| Max outstanding client calls | 1 | 1 | 2 |
| Max dispatch lag, ms | 7.344 | 5.004 | 2.510 |

Total: **2,100 measured HTTP requests**, all successful
(1,680 GET and 420 POST); an additional 60 warm-up HTTP requests
also succeeded.

## What the result proves

At each of these three prescribed offered rates, the short-lived
single-instance loopback lab completed its synthetic workload
without unexpected responses. No Hikari acquisition queue or
active PostgreSQL wait was observed in the **250-ms snapshots**.
The client generator kept its outstanding work under the 32-request
safety ceiling and dispatch lag was small relative to stage duration.

The successful 100 RPS stage is **not** maximum sustainable
throughput or evidence that 4 connections is the correct production
pool size. The pool never approached saturation in the samples
(maximum active = 1), and a single read key plus uniform merchant
make this workload unusually cache-friendly. Database wait sampling
can miss transient waits between polls.

Read and write p95/p99 **decreased** as offered RPS increased.
Possible explanations include JVM/JIT warm-up, database and OS
cache effects, statistical noise and run-to-run conditions. Each
stage initialized a fresh application/database; we cannot attribute
the improvement to higher traffic without repeated randomized-order
trials and hardware utilization data.

## Engineering decision

**PASS — Milestone 16 25/50/100 RPS short-run rate ladder.**
Preserve the current test configuration. Do not tune Hikari
or promise production SLOs from this evidence. The passing Java
and Security CI workflows provide separate regression/security
coverage, not performance certification.

For the next increment, first improve benchmark validity before
escalating offered rate: vary read keys and merchants, retain a
bounded dataset, measure machine CPU/JVM GC, include distribution
of Hikari occupancy (not only max), and add repeated longer stages
or multiple trials. Introduce explicit stop thresholds, keep all
load local and ensure synthetic POST writes remain idempotent.
