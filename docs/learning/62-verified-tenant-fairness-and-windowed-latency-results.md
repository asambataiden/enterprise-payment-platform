# Chapter 62 — Verified Tenant Distribution and Windowed Latency: Results Explained

> **Milestone 19: tested and documented.** Local evidence submitted
> on 2026-11-10 for commit `217a8c87b506ecfbf9d1bf6bcce10093aaf80627`.
> These are **synthetic**, loopback-only experiments with an isolated
> PostgreSQL database, not real payments or production performance SLOs.

## 1. Reproduce the experiment

The developer ran the following from inside the checked-out
`feat/payment-ledger` repository:

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger
mvn -B --no-transfer-progress -f "$ROOT/pom.xml" \
  -Dtest=MixedHttpTenantDistributionTest test
bash -n "$ROOT/ops/benchmarks/http/run-sustained-local.sh"
bash "$ROOT/ops/benchmarks/http/run-sustained-local.sh"
```

The run started at **2026-11-10T10:02:12Z**. The isolated
distribution-test class completed **2 tests, 0 failures, 0 errors,
0 skips, BUILD SUCCESS**. The subsequent sustained runner completed
**both** stages with one JUnit test per stage and `BUILD SUCCESS`.
The measured source SHA printed by the runner was
`217a8c87b506ecfbf9d1bf6bcce10093aaf80627`.

The *tested code SHA* also passed:
- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42795284443)
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42795284860)

The opt-in performance experiments are **not** executed by Java CI.
The timing data below comes from the supplied local terminal output,
not GitHub Actions.

## 2. Everyday-language overview

Imagine four shops sharing one checkout-service system. One shop
makes most requests, while the others make fewer. We want to know
if all shops get a reasonable experience, and whether the service
slows down during a continuous period.

A successful overall average could hide one unhappy smaller shop.
That is why this test collects **per-merchant GET and POST counts**
and latency statistics, in addition to total latency and
**ten-second windows**.

The original benchmark had a **correlated selector bug**:
all POSTs went to merchant 0 even though the overall request
distribution was described as 70/10/10/10.
Chapter 61 explains how we corrected the formula and added
a unit test to prevent recurrence.

## 3. Measured complete-stage outcomes

| Observed metric | 25 RPS for 30 sec | 50 RPS for 60 sec |
| --- | ---: | ---: |
| GET, HTTP 200 | 600 | 2,400 |
| POST, HTTP 201 | 150 | 600 |
| Unexpected responses | 0 | 0 |
| Unavailable responses | 0 | 0 |
| Total measured requests | 750 | 3,000 |
| Completed rate | 25.03 RPS | 50.01 RPS |
| Elapsed seconds | 29.967 | 59.989 |
| GET p50 (ms) | 5.799 | 3.881 |
| GET p95 (ms) | 8.713 | 6.367 |
| GET p99 (ms) | 9.877 | 8.000 |
| POST p50 (ms) | 7.476 | 5.696 |
| POST p95 (ms) | 10.438 | 8.432 |
| POST p99 (ms) | 15.173 | 10.140 |

All **3,750 measured requests** succeeded, comprising **3,000 GETs
and 750 POSTs**. The two stages also used 20 successful unmeasured
warm-up calls apiece (16 GET + 4 POST per stage).

## 4. Ten-second windows — can we spot progressive slowing?

Each window is assigned by **planned dispatch sequence**, not the
HTTP response completion timestamp. GET and POST are always
reported separately.

### Stage 1 — 25 RPS; each window = 200 GET + 50 POST

| Scheduled window | GET p95 (ms) | POST p95 (ms) | GET p99 (ms) | POST p99 (ms) |
| --- | ---: | ---: | ---: | ---: |
| 0–10 s | 8.950 | 11.632 | 9.732 | 25.819 |
| 10–20 s | 8.594 | 10.438 | 10.481 | 15.173 |
| 20–30 s | 7.808 | 9.632 | 9.063 | 10.012 |

### Stage 2 — 50 RPS; each window = 400 GET + 100 POST

| Scheduled window | GET p95 (ms) | POST p95 (ms) | GET p99 (ms) | POST p99 (ms) |
| --- | ---: | ---: | ---: | ---: |
| 0–10 s | 7.400 | 9.894 | 9.192 | 12.125 |
| 10–20 s | 6.420 | 8.743 | 8.356 | 10.442 |
| 20–30 s | 5.573 | 7.992 | 6.767 | 8.667 |
| 30–40 s | 5.395 | 7.962 | 6.937 | 8.147 |
| 40–50 s | 5.300 | 6.999 | 6.799 | 7.555 |
| 50–60 s | 5.454 | 7.793 | 6.774 | 8.432 |

**Interpretation:** no increasing p95 trend was observed. Instead
latency generally decreased during both stages, with small
late variation. Warm-up, JIT, cache, and host conditions
are plausible explanations, but the experiment cannot assign
causality.

**Important:** Stage 1's first ten-second write window has
p99 **25.819 ms** even though the full 30-second write p99
was **15.173 ms**. That is mathematically possible:
per-window percentiles have fewer samples and select a
different rank in the sorted observations. Never average
percentiles to obtain a full-stage percentile.

## 5. Fairness-by-merchant observations

### Stage 1 — 25 RPS for 30 sec

| Merchant | GET count | POST count | GET p95 ms | POST p95 ms | POST p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 — busiest | 420 | 105 | 8.363 | 10.438 | 11.632 |
| 1 | 60 | 15 | 9.063 | 15.173 | 15.173 |
| 2 | 60 | 15 | 9.171 | 9.745 | 9.745 |
| 3 | 60 | 15 | 8.809 | 25.819 | 25.819 |

Every request succeeded. Merchant 3's high 25.819-ms POST
p95/p99 came from **only 15 POSTs**; with nearest-rank
percentiles, p95 and p99 may both equal the maximum when
the sample size is 15. Do not label this sustained unfairness.

### Stage 2 — 50 RPS for 60 sec

| Merchant | GET count | POST count | GET p95 ms | POST p95 ms | POST p99 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 0 — busiest | 1,680 | 420 | 6.328 | 8.396 | 10.140 |
| 1 | 240 | 60 | 6.357 | 8.080 | 10.638 |
| 2 | 240 | 60 | 6.372 | 8.622 | 11.349 |
| 3 | 240 | 60 | 6.586 | 8.512 | 9.162 |

At this rate the read p95 measurements were close across
the four merchants (6.328–6.586 ms). POST p95 values
were also close (8.080–8.622 ms). This is evidence of
**similar observed response times during this particular
test**, not proof of resource-level tenant isolation or
a contractual fairness guarantee. The cold-tenant write
groups contain 60 calls each and remain too small for
reliable p99-based fairness claims.

## 6. Connection pool, database and JVM

| Observed metric | 25 RPS / 30 s | 50 RPS / 60 s |
| --- | ---: | ---: |
| DB/pool samples | 120 | 240 |
| Average sampled active Hikari | 0.083 | 0.063 |
| Average sampled idle Hikari | 0.917 | 0.938 |
| Max sampled active Hikari | 1 | 1 |
| Pending connection samples | 0 | 0 |
| Max sampled PostgreSQL waits | 0 | 0 |
| Sampling failures | 0 | 0 |
| Max client outstanding | 1 | 1 |
| Max schedule lag (ms) | 5.445 | 8.911 |
| Process CPU seconds | 7.236 | 16.988 |
| GC collections | 2 | 6 |
| Heap used at reporting (MiB) | 57.01 | 121.02 |
| JVM thread count at reporting | 39 | 39 |

Hikari's maximum configured connections = **4**, but the
largest active value observed at the quarter-second snapshots
was **1**. No queueing for connections was sampled and no
PostgreSQL waits were observed at the sampling instants.

Neither result proves that no microsecond-scale wait ever
occurred. The process CPU seconds quantify total consumed
CPU work during a stage, not process CPU utilization rate.
The heap samples are not a memory-leak test.

## 7. Correctness checks versus statistical claims

**Passed checks:**
- The distribution test verifies the requested per-operation tenant
  counts over 50-request cycles.
- Both HTTP stages completed all planned calls at the requested rate
  with no unexpected status.
- Every ten-second window and merchant/operation group was present.
- Hikari/DB monitoring coverage and zero-sampler-failure checks passed.
- The test did not exceed its client in-flight cap.

**What remains unproven:**
- Production SLOs, maximum sustained throughput and horizontal scaling.
- Tenant fairness under higher load or very different data sizes.
- A durable absence of contention or long-term memory leaks.
- Performance in a real network with TLS, load balancers and
  a real identity provider.
- Real payments, card processing, banking or settlement.

## 8. Engineering decision

**PASS — Milestone 19's tested distribution and reporting gates.**
Preserve the fixed load ceilings and four-connection test
configuration. Do not tune Hikari based on these results.

For the next increment, improve statistical confidence by
making cold-tenant sample counts larger and running
independently repeated experiments. Add per-tenant offered/
completed rates and explicit thresholds **only when the
requirements justify those thresholds**.

## 9. Beginner checkpoint and interview practice

**Q: Why is one total p95 not enough for a four-merchant service?**
Because one tenant may have a much worse experience, even if
the weighted whole-system percentile looks acceptable.

**Q: Why do we print the sample count beside the p95?**
A percentile from 15 requests is much less reliable than
one from 420 requests. Always consider the sample size.

**Q: Why not call merchant 3 'unfairly treated' at 25 RPS?**
It had only 15 writes and a high near-maximum observation.
We need more samples, repeats and defined fairness criteria.

**Q: How is a workload-distribution unit test different from a
performance benchmark?** The unit test proves our synthetic
request selection arithmetic; the benchmark measures how
the application responds to the selected traffic. Both
are necessary for defensible measurements.

**Q: What should you say at a senior Java interview?**
"We caught a test-generator bias where all writes targeted
one tenant. We corrected it, proved the intended distribution
independently with JUnit, then added ten-second and per-merchant
HTTP latency breakdowns. The corrected 25/50 RPS experiments
completed all 3,750 synthetic requests, and no pool queue
was observed. We did not treat this as production SLO
certification because our cold-tenant groups and the
developer-machine setup limit generalization."
