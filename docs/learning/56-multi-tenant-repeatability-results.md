# Chapter 56 — Repeated Multi-Tenant HTTP Benchmark: Verified Local Evidence

> **Correction discovered in Milestone 19 (Chapter 61):** The earlier
> benchmark's merchant-selection logic unintentionally directed **all
> POST/write requests to merchant 0**, because both the POST selector
> and merchant selector depended on correlated modulo values. The
> overall 70/10/10/10 tenant assumption **did not hold separately for
> writes**. The recorded outcomes remain accurate for the workload
> actually executed, but must **not** be presented as evidence of
> per-tenant write fairness. The scheduler was corrected afterward,
> with a dedicated unit test and per-tenant statistics. See
> [Chapter 61](61-tenant-fairness-and-windowed-latency-from-zero.md).
>

## Execution provenance

The developer executed `ops/benchmarks/http/run-repeated-rate-ladder.sh`
on **2026-11-10** (started 09:08:29 UTC). Tested revision:
`4a6caf07fb30b6c9530b7010956da9ffd10bf7f7`.

The runner completed the six independent isolated Testcontainers
PostgreSQL 17 and loopback Spring Boot trials in this exact order:
**25 → 50 → 100 → 100 → 50 → 25 requests/second**.

The provided console evidence records `BUILD SUCCESS` for every
trial, with one JUnit test executed per stage and no failures, errors
or skipped tests. This benchmark uses **signed test JWTs**, four
synthetic merchants, eight seeded payment records per merchant,
a 70/10/10/10 tenant skew, an 80% GET/20% POST request split, eight
bounded HTTP workers and no real financial transactions.

The same tested revision passed:
- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42788070309)
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42788070382)

CI does **not** execute this opt-in load test; the performance numbers
come from the developer's local console evidence.

## Per-trial measurements

| Trial | Offered RPS | GET success | POST success | GET p50 ms | GET p95 ms | GET p99 ms | POST p50 ms | POST p95 ms | POST p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 25 | 240 | 60 | 5.493 | 8.313 | 9.859 | 6.961 | 9.521 | 10.984 |
| 2 | 50 | 480 | 120 | 4.151 | 6.513 | 7.613 | 5.462 | 7.354 | 8.100 |
| 3 | 100 | 960 | 240 | 3.096 | 4.721 | 5.902 | 4.671 | 6.877 | 8.826 |
| 4 | 100 | 960 | 240 | 3.084 | 4.642 | 5.901 | 4.518 | 6.354 | 8.208 |
| 5 | 50 | 480 | 120 | 4.298 | 6.569 | 7.841 | 5.595 | 8.554 | 12.358 |
| 6 | 25 | 240 | 60 | 4.963 | 8.170 | 11.014 | 6.598 | 9.347 | 11.560 |

**Totals:** 4,200 successful measured HTTP calls (3,360 GET
responses with HTTP 200; 840 POST responses with HTTP 201), no
unavailable responses and no unexpected statuses. There were
120 additional unmeasured warm-up requests across six trials.

| Trial | Completed RPS | Avg sampled Hikari active | Max active | Pending samples | Max sampled DB waits | CPU seconds | GC collections | Heap used MiB | Max outstanding | Max schedule lag ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 25.06 | 0.063 | 1 | 0 | 0 | 3.046 | 1 | 56.90 | 1 | 5.084 |
| 2 | 50.05 | 0.083 | 1 | 0 | 1 | 4.176 | 1 | 104.80 | 1 | 5.025 |
| 3 | 100.03 | 0.104 | 1 | 0 | 0 | 6.132 | 2 | 104.70 | 2 | 2.544 |
| 4 | 100.02 | 0.146 | 1 | 0 | 0 | 6.188 | 2 | 104.77 | 2 | 2.561 |
| 5 | 50.03 | 0.146 | 1 | 0 | 0 | 4.428 | 1 | 104.97 | 1 | 4.997 |
| 6 | 25.06 | 0.063 | 1 | 0 | 0 | 2.963 | 1 | 72.89 | 1 | 5.098 |

Each trial collected **48** pool/database samples with **zero
monitoring failures**. The database observation of 1 in trial 2 is
a maximum count of active backends with non-null wait events at an
individual sample; it is not proof of a persistent lock or resource
contention problem.

## Repeated-trial interpretation

The two 25-RPS runs have GET p95 values 8.313/8.170 ms and
POST p95 9.521/9.347 ms. At 50 RPS, GET p95 is 6.513/6.569 ms
and POST p95 is 7.354/8.554 ms. At 100 RPS, GET p95 is
4.721/4.642 ms and POST p95 is 6.877/6.354 ms.

Lower latency at higher offered rates is **observed, not explained**.
These short runs may be affected by JIT compilation, cache effects,
Docker Desktop scheduling, CPU-frequency effects and variation in
the local machine. The experiment is not a robust causal explanation
or proof of production throughput scaling.

At the 250-ms sampling resolution, pool pressure remained low:
maximum active connections were 1 even though the pool allowed 4,
and there were no pending-acquisition observations. This does not
mean that unobserved transient contention is impossible. CPU
seconds rise with offered traffic but do not, by themselves,
establish CPU utilization percentage or a bottleneck.

## Engineering decision

**PASS — Milestone 17 repeatability and multi-tenant safety gates.**
The opt-in workload completed every trial successfully with valid
instrumentation, and the tested Java and Security CI gates passed.
Keep Hikari settings unchanged: there is no measured connection-pool
saturation to justify tuning.

Next: prioritize longer measured windows, larger and more varied
datasets, a controlled distribution of read/write operations, GC/CPU
utilization and independent repetitions. Confirm request accuracy
and merchant isolation under concurrent retries separately.

These are **synthetic localhost-only** measurements. Do not
represent the result as a production SLO, payment processor
capacity, or maximum sustainable throughput.
