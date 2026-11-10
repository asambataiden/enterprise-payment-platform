# Chapter 60 — Sustained Mixed HTTP Workload: Actual Results and Beginner Walkthrough

## At a glance

**Verified locally:** two isolated synthetic HTTP load stages passed.
The developer executed `ops/benchmarks/http/run-sustained-local.sh`
starting **2026-11-10 09:40:13 UTC**, using Git revision
`558629aaa41f8ca860685e0f91bff64808383ef7`.

Both stages returned `BUILD SUCCESS` with one explicitly selected
JUnit performance-lab test per stage, zero failures, zero errors,
zero skips and no unexpected HTTP responses. The test process
used locally issued signed test JWTs and an ephemeral PostgreSQL 17
container for each stage. No real financial processing.

Verified CI on **the tested implementation revision**:
- [Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42792222190): success.
- [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42792222239): success.

Those CI workflows do not execute the opt-in load experiment.
The following performance measurements originate from the provided
local console output, not from GitHub CI.

## Observed request outcomes

| Metric | Stage 1 | Stage 2 |
| --- | ---: | ---: |
| Target sending rate | 25 RPS | 50 RPS |
| Target duration | 30 sec | 60 sec |
| Measured GETs, all HTTP 200 | 600 | 2400 |
| Measured POSTs, all HTTP 201 | 150 | 600 |
| Total measured requests | 750 | 3000 |
| Unavailable HTTP responses | 0 | 0 |
| Unexpected HTTP responses | 0 | 0 |
| Measured elapsed time | 29.972 sec | 59.991 sec |
| Completed RPS | 25.02 | 50.01 |
| GET p50 | 5.900 ms | 3.957 ms |
| GET p95 | 8.762 ms | 6.322 ms |
| GET p99 | 10.437 ms | 7.954 ms |
| POST p50 | 7.418 ms | 5.615 ms |
| POST p95 | 10.788 ms | 8.933 ms |
| POST p99 | 12.134 ms | 10.363 ms |

**3,750 measured HTTP requests succeeded**, consisting of
3,000 GET and 750 POST operations. There were also 40 successful
unmeasured warm-up requests: 32 reads and 8 writes.

## Runtime observations

| Observation | Stage 1 | Stage 2 |
| --- | ---: | ---: |
| Tenant identities | 4 | 4 |
| Seeded records per merchant | 8 | 8 |
| Pool/DB telemetry samples | 120 | 240 |
| Max sampled Hikari active connections | 1 | 1 |
| Avg sampled Hikari active connections | 0.100 | 0.075 |
| Avg sampled Hikari idle connections | 0.900 | 0.925 |
| Samples with pending DB acquisition | 0 | 0 |
| Max sampled pending DB acquisitions | 0 | 0 |
| Max sampled active PostgreSQL waits | 0 | 1 |
| Monitoring failures | 0 | 0 |
| Max outstanding client requests | 1 | 1 |
| Max schedule lag | 5.209 ms | 5.101 ms |
| JVM process CPU time in measured stage | 7.394 sec | 17.064 sec |
| GC collections during measured stage | 2 | 6 |
| Heap used at observation | 72.55 MiB | 104.94 MiB |
| JVM threads at observation | 39 | 39 |

The 60-second stage observed at most **one active PostgreSQL
backend with a non-null wait event** in its sampling snapshots.
This is *not* evidence of sustained DB lock contention and
cannot identify the wait type from these aggregate data.
At 250-ms sampling frequency, brief waits or busy connections
can escape observation.

Process CPU *seconds* measure accumulated CPU work, not CPU
utilization percentage. The 60-second stage contains 4x the
requests of the 30-second stage (3000 vs 750), so comparing their
CPU seconds or heap values as if all conditions were identical
would be misleading.

## Explained for beginners

**What did the test do?** One program sent requests to a local
Spring Boot web server. 80% asked for stored payment data (GET),
and 20% submitted new simulated payment records (POST). A
temporary PostgreSQL database stored the records. Another
piece of code periodically checked whether DB connections
were busy or waiting.

**What does 25 RPS mean?** The test *attempted* to start 25 HTTP
requests every second. At the end of the first stage it
completed 750 requests in about 30 seconds: approximately
25 requests per second.

**What is p95?** If we arrange request times from shortest to
longest, p95 marks the point at which about 95% of the
measured requests are at or below that duration.
For stage 2, the GET p95 was **6.322 milliseconds** and POST
p95 was **8.933 milliseconds**. Those are measured
distributions for *this local experiment*, not guarantees.

**Why didn't the database pool queue?** HikariCP could hold
four connections, but the test recorded at most one active
connection at each sampling instant. So this experiment did
not create *observed* pool saturation. That is a good result
for these rates, but tells us nothing about the true breaking
point of a larger or multi-instance deployment.

**Why did the higher rate show lower measured latency?**
It could be related to JVM warm-up, CPU clocking, caching,
operating-system scheduling or sampling variation. Because
the stages have different lengths and fresh app/DB contexts,
we cannot make a causal claim that increasing traffic
improves response speed.

## Evidence lifecycle and deployment limits

The original test run recorded timestamped logs under
`target/http-load-lab/`. These are local build artifacts,
not files committed into Git. This chapter preserves their
reported measurements; raw log reproduction requires the
developer's local files.

The load ran on a development machine, with one application,
short bounded stages, small hot read datasets, isolated
PostgreSQL and no external traffic generator, TLS gateway,
production identity provider, broker workload, settlement
network or real financial transactions.

**Decision: PASS — Milestone 18 Part B's functional and
monitoring gates.** Keep Hikari configuration unchanged.
Do not claim production SLO conformance or maximum
sustainable throughput. Next, refine the benchmark using
time-bucketed latency, per-tenant fairness and larger
datasets before attempting longer or higher-load trials.

## Tutorial comprehension and interview questions

1. If 750 requests completed in roughly 30 seconds,
   what is the throughput and why is it not necessarily
   the maximum possible throughput?
2. Why does a zero count of sampled pending Hikari
   acquisitions not prove zero waits at every instant?
3. Why should POST and GET have separate percentile numbers?
4. What would it mean if completed RPS fell below offered RPS
   and scheduling lag or pending calls increased?
5. How would we distinguish CPU pressure from SQL/DB
   contention without guessing from one metric?
6. Why is the successful localhost run not a certified
   financial-payment processing capacity test?

### 30-second interview answer

> We built a controlled, opt-in Java HTTP performance experiment
> using signed synthetic merchant JWTs, Spring Boot and disposable
> PostgreSQL. In independent 30- and 60-second stages, all 3,750
> simulated read/write calls returned the expected status at
> approximately 25 and 50 completed requests per second.
> We collected client-side latency, Hikari pool telemetry,
> sampled PostgreSQL waits and JVM observations. No pool queueing
> was observed; we explicitly do not interpret this as
> production capacity, maximum throughput or real payment readiness.
