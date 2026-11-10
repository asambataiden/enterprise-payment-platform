# Chapter 45 — PostgreSQL Benchmark Evidence and Engineering Decision

## Evidence provenance

- Execution date: 2026-11-10 (UTC).
- Tested revision: `4687e8eb1e3c92ac7a4e02810b731db7f24b27a3`.
- PostgreSQL image: `postgres:17-alpine`.
- Harness: `ops/benchmarks/postgresql/run.sh` and `benchmark.sql`.
- Isolation: disposable Docker container, no published ports, network disabled,
  temporary database tables, synthetic values only.
- Data volume: 100,000 synthetic payments and 100,000 synthetic outbox events.
- Local execution log supplied by developer from the Mac lab on Nov 10, 2026;
  do not commit local machine paths, credentials or complete environment logs.
- No production traffic, regulated funds or customer data involved.

## Observed results — one execution per scenario

| Query scenario | Before V9-equivalent indexes | After | Observed reduction | Access plan before / after |
| --- | ---: | ---: | ---: | --- |
| Merchant newest-first, LIMIT 26 | 6.028 ms | 0.036 ms | 99.40% | Seq Scan + top-N Sort / ordered Index Scan |
| Merchant + status + keyset, LIMIT 26 | 6.444 ms | 0.032 ms | 99.50% | Seq Scan + top-N Sort / Index Scan and residual status Filter |
| Merchant-owned history, LIMIT 101 | 4.313 ms | 0.126 ms | 97.08% | Seq Scan + Sort / Bitmap Index Scan + Bitmap Heap Scan + Sort |

Values are taken from **`Execution Time` inside PostgreSQL EXPLAIN ANALYZE**,
not from psql's `Time:` lines (which include client and formatting
overhead). Reported percentage is `100 * (before - after) / before`.
Do not market these figures as end-to-end API or production latency.

### Why each plan changed

**Newest-first payment search:** Before, PostgreSQL scanned all 100,000
rows, rejected 90,000 for the tenant predicate, sorted the 10,000
matches and returned 26. After, the ordered composite index returned
26 matches with no explicit sort. Buffer count changed from
`local read=1235` (with pre-index temp-table writes reported) to
`local read=7`.

**Filtered keyset:** Before, PostgreSQL scanned 100,000 rows, removed
94,019 and sorted the remaining 5,981. After, the scan used merchant
and tuple-position conditions in the index, applied `status` as a
residual filter, rejected 12 candidate rows and returned 26.
The `status` column is **not part of the index**. This supports
the decision to defer a status-specific index until representative
selectivity and update costs are established.

**Payment history:** Before, PostgreSQL scanned 100,000 rows and
removed 99,900. After, the planner used a bitmap index scan/heap scan
to locate 100 matching rows and performed a small quicksort. The
ordered index did **not** remove the sort in this chosen plan. This is
valid optimizer behavior and should not be described as a pure ordered
index scan.

## Index footprint (synthetic temp tables)

- `bench_idx_payments_merchant_created_id`: 5,776 KiB.
- `bench_idx_outbox_merchant_payment_created_id`: 7,568 KiB.
- Combined: 13,344 KiB = approximately **13.03 MiB**.

These are measurements of the synthetic indexes, not production storage
estimates. Insert, update, vacuum and replication overhead were not
measured. The benchmark's first-before/second-after execution order
and warmed cache can bias relative timing.

## Decision and acceptance

**GO — retain the two Flyway V9 indexes**, based on consistent
improvements to all three observed query paths and supported plan
changes. This is an evidence-backed *lab* decision, not certification
of an SLO. **NO GO** for extra status indexes or caching without
representative workload and write-amplification evidence.

At tested revision `4687e8eb`, GitHub Java CI and Security CI passed:
- Java CI: https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42773005584
- Security CI: https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42773005452

### Next measurement increment

Use repeatable measurements with multiple warm-up and measured
iterations, randomized scenario ordering, and independent runs.
Capture median/p95 across samples, explicit sample counts,
connection pool saturation under concurrent requests,
plus indexed/unindexed **write throughput**. Where practical, test
varied tenant skew, history cardinalities, status distributions
and deeper keyset positions. Do not extrapolate three one-off SQL
plans to API response-time targets.

## Interview discussion

1. Why did PostgreSQL choose Bitmap Heap Scan plus Sort for history?
2. What do `Rows Removed by Filter` and local buffer hits reveal?
3. Why do EXPLAIN execution times differ from psql wall-clock times?
4. How can cache warming make before/after comparisons misleading?
5. Why do read indexes affect insertion and update throughput?
6. What evidence is needed before adopting an API p95 SLO?
