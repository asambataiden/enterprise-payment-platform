# Chapter 46 — Repeated PostgreSQL Read and Write Measurements

## Objective

The single-pass benchmark from Chapter 45 found substantially improved
PostgreSQL plans for the two Flyway V9 indexes. Those findings do not
establish production p95 latency or quantify secondary-index write overhead.

This separate, opt-in lab adds repeat measurements and controlled inserts.
It intentionally does **not** change payment application code, migrations
or production database settings.

## Execute

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger
bash "$ROOT/ops/benchmarks/postgresql/run-statistical.sh"
```

The runner requires running Docker Desktop, Bash, Git and OpenSSL.
It starts PostgreSQL 17 in a disposable container with `--network none`,
no published ports and an ephemeral 1 GiB data tmpfs. It uses neither the
application DB connection nor external credentials. Everything is synthetic;
the entire isolated container is removed on exit. Capture results under
`target/postgresql-benchmark/` and share the generated log for analysis.

## Experimental design

Four synthetic read tables are created: payment and outbox-history datasets,
each with an indexed and an unindexed variant. Every variant starts with
the same 100,000 rows. Both payment variants have identical primary-key
indexing; only the indexed version has the extra V9-style read index.

Read scenarios:

| Scenario | Predicate | Page |
| --- | --- | --- |
| Newest-first | Merchant ownership | 26 rows |
| Filtered keyset | Merchant, status, tuple cursor | 26 rows |
| History | Merchant and payment ID | Up to 101 rows |

Each scenario runs **8 warm-up pairs plus 40 measured pairs** per
variant. The order alternates by round to reduce systematic
first/second ordering bias. The SQL records in-server elapsed milliseconds
via `clock_timestamp()` around an aggregate over the bounded query.
The aggregate is intentional: it causes non-indexed projected values to
be read rather than benchmarking index-only access that the real
API query cannot necessarily use. Timings therefore contain some
SQL execution and aggregate overhead; they are not pure planner-reported
node times or HTTP response times.

PostgreSQL computes sample median, p95, minimum and maximum. These
are **serial, warm-cache sample percentiles**, not a production SLA
and not the p95 of concurrent merchant HTTP requests. Plans from
`EXPLAIN (ANALYZE, BUFFERS)` are captured separately.

For write measurements, two fresh real tables have the same primary
key and identical insert payload. The indexed table adds one
`(merchant_id, created_at DESC, id DESC)` secondary index.
Each variant receives **12 alternating batches of 1,000 INSERTs**.
The log includes p50/p95 per batch, rows inserted and storage size.

**Caveat:** PostgreSQL groups these writes inside one DO statement;
their timing includes SQL execution and index maintenance but does
not isolate individual transaction commits, fsync, or replication.
This is not a WAL durability throughput or steady-state TPS benchmark.

## Validate evidence and avoid overclaiming

Check that all queries returned measured rows and both variants have
40 samples each, with 12 insert batches and 12,000 rows per write
variant. Compare p50 and p95 alongside plan nodes and buffer usage;
check write batch timings and secondary-index footprint before
deciding whether the extra index is worth its cost. Repeat in separate
containers to assess run-to-run variance, background host activity
and cold-start behavior.

The lab is single-session and serial. It **does not** validate:
multi-user contention, Hikari pool saturation, lock waiting,
p95 HTTP latency, commit fsync latency, autovacuum under sustained
updates, replication lag or production capacity.

An actual performance-release gate needs a representative workload,
concurrency and scenario distribution, adequate repetitions,
monitoring and measured SLOs. Do not add a status index, cache,
connection pool resize or new infrastructure solely from this lab.

## Security and operational boundaries

Never point benchmark SQL at a real database. The runner launches
and connects to its own isolated PostgreSQL only and destroys it.
Do not use real merchant records or credentials. The lab script is
not executed by the ordinary Maven test phase or by GitHub CI;
Java and Security CI continue to gate application correctness.

## Interview topics

1. Why measure p50/p95 from repeated executions instead of one EXPLAIN?
2. What is the difference between database query latency and API p95?
3. What limitations remain with fixed alternating test order?
4. How does a secondary index affect INSERT and UPDATE costs?
5. Why must transaction commit durability and contention be benchmarked separately?
6. How would you build a multi-replica, connection-pool saturation test?
