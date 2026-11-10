# Chapter 47 — Statistical PostgreSQL Benchmark: Measured Results

## Provenance and verification

The developer ran the isolated statistical benchmark on a Mac
on 2026-11-10 at 07:46:39 UTC using:
- Git revision: `5309e6f14fe64647e2378dac79f414750f7498fc`
- PostgreSQL: `postgres:17-alpine`
- Runner: `ops/benchmarks/postgresql/run-statistical.sh`
- SQL: `ops/benchmarks/postgresql/statistical.sql`
- Isolation: Docker `--network none`, no published ports,
  ephemeral database, synthetic data only
- Fixture sizes: 100,000 payment rows and 100,000 history rows
  per variant, 12,000 inserted write rows per variant

The SQL completed without the previous dollar-quote syntax error.
Its final validation block completed, the output showed all expected
fixture counts, and no error was reported.

The tested commit also passed
[Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42776592807)
and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42776593161).
CI checks the application but does **not** execute this optional
statistical benchmark. The benchmark success is evidenced by
the developer-provided local run.

## Read performance: 40 measured samples per variant

Each scenario used eight warm-up pairs and 40 measured samples per
variant; the runner alternated indexed/plain execution order.

| Scenario | Plain median (ms) | Indexed median (ms) | Plain p95 (ms) | Indexed p95 (ms) |
| --- | ---: | ---: | ---: | ---: |
| Newest-first payment search | 3.689 | 0.040 | 3.758 | 0.073 |
| Merchant + status + keyset | 4.519 | 0.049 | 4.707 | 0.075 |
| Merchant payment history | 3.395 | 0.096 | 3.908 | 0.194 |

The indexed median reductions were approximately:
- Newest-first: **98.92%**
- Filtered keyset: **98.92%**
- History: **97.17%**

The measured plans explain the differences:

- Payment search before indexing used a sequential scan and top-N
  sort with **1,280 shared buffer hits**. The indexed variant used
  an ordered index scan with **7 shared buffer hits**.
- History before indexing used sequential scan and sorting with
  **1,152 shared buffer hits**. With its composite index, the
  optimizer chose bitmap index/heap scans and a small sort with
  **104 shared buffer hits**.
- Filtered-keyset percentile results were collected, but its
  execution plan was not included among the separately displayed
  EXPLAIN plans in this second experiment. The first experiment
  documented that plan; do not claim this run independently
  inspected it.

## Insert overhead: 12 batches per variant

Both write tables have identical primary keys. The indexed variant
adds one V9-style secondary index. Each received 12 batches of
1,000 synthetic rows (12,000 total).

| Metric | Plain | Indexed |
| --- | ---: | ---: |
| Median batch (ms) | 1.888 | 2.985 |
| p95 batch (ms) | 2.226 | 3.163 |
| Observed batches per second | 529.404 | 345.453 |

The indexed median batch execution was approximately **58.1% longer**.
The provided batches-per-second statistic is computed from the sum of
measured batch execution times; it is **not** end-to-end throughput
under concurrency or durable commits.

Index storage in this fixture:
- Payment read index: 5,776 KiB
- History read index: 7,568 KiB
- Write secondary index: 1,200 KiB after 12,000 inserted rows

## Engineering decision

**Retain both existing Flyway V9 read indexes** for the demonstrated
merchant-search and history workload. The large observed read gains
make them reasonable for this educational workload, despite the
measurable insert overhead.

Do **not** introduce an additional status index, query cache,
or connection-pool changes based solely on these serial lab results.

This study is limited by synthetic fixtures, warm-cache conditions,
fixed query distribution, a single host and database session,
small sample counts and absence of transaction-commit/concurrency
measurements. The p95 figures are **database sample percentiles**,
not production API SLOs or performance guarantees.

## Next increment: contention and pool saturation

Use a separate controlled load test with concurrently executing
requests against an isolated PostgreSQL instance. Measure:
- HikariCP active/idle/pending connections and acquisition waits
- request throughput and end-to-end response p50/p95/p99
- PostgreSQL active sessions, locks and wait events
- transaction error rates, timeouts, retries and idempotency
- read/write mix and tenant skew
- resource usage on the local machine

Run in a disposable lab, prevent real funding operations, record
sample counts and hardware context, and do not claim production
capacity without representative deployment conditions.
