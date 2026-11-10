# Chapter 64 — Two Repeated 100-RPS Trials: Verified Evidence and Beginner Explanation

> **Milestone 20, local execution recorded:** November 10, 2026. Tested commit
> `b7362f1025af49f6789d83d75c93c58ceeb76486`.
> This is a synthetic simulated-payment exercise; it cannot demonstrate
> real-world payment authorization, financial settlement, production
> availability or compliance.

## Start with the result, not a prediction

The developer executed
`bash ops/benchmarks/http/run-repeated-sustained-local.sh`,
starting at **2026-11-10 10:27:01 UTC**. The runner completed two
independent test processes, each with its own Spring context,
disposable PostgreSQL 17 database, signed synthetic JWTs and
**6,000 measured HTTP requests over 60 seconds**.

Both Maven tests reported **1 test, 0 failures, 0 errors, 0 skipped;
BUILD SUCCESS**. Together the stages measured **12,000 successful
calls (9,600 GET, 2,400 POST)** and no HTTP 503 or unexpected
status. There were also 20 warm-up calls per trial, not included
in measured totals.

The tested code revision passed:
- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42796897544)
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42796897530)

The opt-in load trial **does not run inside CI**; these latency
measurements come from the local terminal output.

## 1. Overall client-observed measurements

| Metric | Trial 1 | Trial 2 |
| --- | ---: | ---: |
| Measured GET successes | 4,800 | 4,800 |
| Measured POST successes | 1,200 | 1,200 |
| Unexpected / unavailable | 0 / 0 | 0 / 0 |
| Completed RPS | 100.01 | 100.01 |
| Measured seconds | 59.995 | 59.996 |
| GET p50 ms | 2.652 | 2.712 |
| GET p95 ms | 4.468 | 4.489 |
| GET p99 ms | 5.903 | 5.826 |
| POST p50 ms | 4.281 | 4.277 |
| POST p95 ms | 6.902 | 6.586 |
| POST p99 ms | 8.178 | 8.447 |

**Beginner explanation:** p95 is the measured response duration
at or below which about 95% of that run's measured requests fell.
Trial 1 GET p95 was 4.468 ms, versus 4.489 ms in trial 2.
The small difference is evidence of similar behavior *here*.
Do not average p95 numbers and call the result a pooled percentile;
we would need the underlying individual request measurements.

## 2. Per-merchant throughput and latency

Each trial used the corrected deterministic workload: **70% of
GET and POST traffic for merchant 0**, and 10% for each of
merchants 1, 2 and 3.

| Merchant / operation | Requests per trial | Completed RPS (approx.) | Trial 1 p95 ms | Trial 2 p95 ms | Trial 1 p99 ms | Trial 2 p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 GET | 3,360 | 56.00 | 4.391 | 4.539 | 5.676 | 5.830 |
| 0 POST | 840 | 14.00 | 7.046 | 6.757 | 8.165 | 8.523 |
| 1 GET | 480 | 8.00 | 4.808 | 4.706 | 6.203 | 6.359 |
| 1 POST | 120 | 2.00 | 5.113 | 5.752 | 5.438 | 7.200 |
| 2 GET | 480 | 8.00 | 4.796 | 4.273 | 7.133 | 5.053 |
| 2 POST | 120 | 2.00 | 7.650 | 6.206 | 10.626 | 8.476 |
| 3 GET | 480 | 8.00 | 4.266 | 4.222 | 5.230 | 5.222 |
| 3 POST | 120 | 2.00 | 5.536 | 6.057 | 6.661 | 7.300 |

Every tenant/operation group completed its full scheduled request
count. But differences remain between smaller tenants' POST
p95 results—for example, merchant 2 POST p95 changed from
7.650 to 6.206 ms. This variation matters; **two runs and
120 writes per cold tenant in each run are not sufficient to
certify statistically meaningful tenant fairness or p99 SLOs.**

## 3. Ten-second window metrics

Each scheduled ten-second window contains 800 GET and 200 POST.
Latency is grouped by intended request sequence, not completion
timestamp.

| Window | Trial 1 GET p95 | Trial 2 GET p95 | Trial 1 POST p95 | Trial 2 POST p95 |
| --- | ---: | ---: | ---: | ---: |
| 0–10 s | 5.311 | 5.150 | 7.381 | 7.445 |
| 10–20 s | 4.579 | 4.355 | 6.954 | 6.739 |
| 20–30 s | 3.857 | 4.323 | 5.562 | 6.206 |
| 30–40 s | 4.283 | 3.984 | 6.721 | 5.878 |
| 40–50 s | 4.046 | 4.104 | 6.643 | 5.741 |
| 50–60 s | 3.820 | 3.959 | 6.519 | 5.793 |

**Interpretation:** neither trial showed an increasing p95
trend over the whole stage. Latencies were generally higher
in the first ten seconds than in the final ten seconds.
Warm-up, JIT compilation, caches and machine scheduling
may play a role; the experiment does not establish causality.

## 4. JVM, database and load generator observations

| Observation | Trial 1 | Trial 2 |
| --- | ---: | ---: |
| Hikari/DB sample count | 241 | 240 |
| Max sampled active Hikari | 1 | 1 |
| Avg sampled active Hikari | 0.062 | 0.038 |
| Avg sampled idle Hikari | 1.855 | 0.963 |
| Samples with pending connections | 0 | 0 |
| Max sampled DB waits | 1 | 0 |
| Monitoring failures | 0 | 0 |
| Max outstanding HTTP calls | 2 | 2 |
| Max scheduling lag ms | 3.065 | 2.556 |
| Process CPU seconds | 21.690 | 21.858 |
| GC collections | 12 | 11 |
| Heap used MiB (report snapshot) | 57.59 | 121.81 |
| JVM threads (report snapshot) | 40 | 39 |

The Hikari pool was capped at four, but **no pending
acquisition was sampled**. Trial 1 captured a maximum of
one PostgreSQL session with a non-null wait event in a
sample; this is not proof of sustained contention. JVM
CPU-seconds are accumulated CPU work, not utilization
percentages. Heap snapshots do not constitute a
memory-leak analysis.

## 5. What was actually demonstrated?

1. The corrected workload scheduling produced the
   intended counts for **each merchant and HTTP operation**.
2. Both independently launched localhost/DB test stages
   completed 6,000 requests successfully at approximately
   100 completed RPS.
3. We have per-merchant and windowed latency figures
   rather than only whole-system aggregates.
4. No sampled Hikari waiting queue or progressive p95
   slowdown was observed.
5. The two overall p95 series are relatively close,
   but some individual tenant tail figures varied.

These observations are **not** sufficient to claim maximum
sustained capacity, production SLAs, tenant fairness under
high contention, horizontal scaling or PCI compliance.

## 6. Engineering decision

**PASS — Milestone 20 local verification.**
Do not alter Hikari configuration based on these results:
no pool saturation was demonstrated.

Before a further stress increase, design a richer
reproducible data corpus, record raw request timings
when feasible, and define explicit comparison questions.
More concurrent load is not necessarily more learning.

## 7. Interview checkpoint

**Question:** Why do two nearly identical whole-system
p95 values not prove equal tenant performance?

**Answer:** The total is dominated by the hot tenant.
Cold tenants have fewer observations, and their write
percentiles differ across the two trials. We separate
the request mix, per-tenant success counts and latency
distributions to avoid hiding unequal behavior.

**Question:** Can you average p95 values from the trials?

**Answer:** No. A pooled percentile must be calculated
from the raw combined observations. Two per-trial p95
values are not enough.

**Question:** What would justify increasing DB pool size?

**Answer:** Documented connection-acquisition contention,
transaction timing and a connection budget, followed by
controlled comparative measurement—not one successful load test.
