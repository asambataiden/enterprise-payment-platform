# Chapter 72 — Validated JVM Evidence and Why the Time Origin Matters

> **Milestone 22 verified on macOS, November 10, 2026.**
> The actual local 100-RPS, 60-second experiment ran on commit
> `701dc6e469b0a1d86a07b7c514c4c8e669245966`.
> All payments were synthetic; the application ran on localhost
> and used disposable PostgreSQL 17.

## 1. What was finally verified?

The third diagnostic attempt succeeded **end to end**:
- The Maven/JUnit workload completed **6,000 measured HTTP calls**
  successfully: 4,800 GET 200s, 1,200 POST 201s, no errors.
- The test printed the real **forked JVM PID 58101** and
  measurement-stage JVM uptime **4,807 ms**.
- The collector created a nonempty JVM GC/safepoint log for the
  matching PID, not for Maven or the preflight JVM.
- The CSV and offline analyzer validated all **6,000** samples.
- The analyzer reported **105 matching JVM GC/safepoint log lines**.
- All four artifacts were retained: Maven log, request CSV, JVM
  log and offline analysis.
- The analyzer reported **zero measured requests taking over 50 ms**.

The code revision also passed
[Java CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42806528655)
and [Security CI](https://github.com/asambataiden/enterprise-payment-platform/actions/runs/42806528283).
The opt-in performance test is **not** part of those CI workflows.

## 2. Observed HTTP and resource data

| Metric | Observed |
| --- | ---: |
| GET samples/successes | 4800 / 4800 |
| POST samples/successes | 1200 / 1200 |
| Achieved RPS | 100.01 |
| Elapsed seconds | 59.995 |
| GET p50/p95/p99 (ms) | 2.623 / 4.342 / 5.416 |
| POST p50/p95/p99 (ms) | 4.220 / 6.704 / 7.809 |
| Max client outstanding | 2 |
| Max schedule lag (ms) | 2.566 |
| Max sampled active Hikari | 1 |
| Max sampled pending Hikari | 0 |
| Max sampled database waits | 0 |
| Monitor failures | 0 |
| GC collections, JVM counter | 12 |
| Matched JVM event log lines | 105 |
| Requests >50 ms | 0 |

There was **no recurrence of the earlier seven groups of
50–148-ms slow requests** in this run. That does not establish
why they appeared in the earlier, separately executed test.

## 3. From first principles: three different clock concepts

### A. Scheduled request position

With 100 offered requests per second, request number 3,017
has an intended dispatch offset of about 30.16 seconds.

That is the *planned* start, not the observed arrival time.

### B. Measured monotonic HTTP offsets

`System.nanoTime()` is sampled immediately before
and after an HTTP send call, then converted to elapsed
milliseconds relative to the measured stage start. These
values capture the client's actual elapsed duration without
being affected by wall-clock adjustment.

### C. JVM uptime event markers

The unified JVM log prints timestamps measured since
the test JVM started. The measurement started at approximately
4,807 ms of JVM uptime. To align a JVM log marker:

```text
stage_relative_jvm_ms = jvm_log_uptime_ms - 4807
```

For example, a JVM marker at uptime 457 ms corresponds to
`457 − 4807 = −4350 ms`: about 4.35 seconds **before**
the measured requests began. The first printed GC/safepoint
markers in the shared output had negative offsets,
meaning they belonged to **startup and test preparation**.

## 4. Important analytical trap

The original analyzer printed the **first 60** matching JVM
event lines. The output showed negative relative offsets in
that printed subset. Because there were **105** matching
lines, we cannot conclude from that excerpt alone that
there were no GC or safepoint events during the measured
60-second stage.

To avoid this error, the analyzer now explicitly reports
matching **log-line counts** in three intervals:

1. **Startup:** event marker offset less than 0 ms.
2. **Measured:** event marker offset from 0 through 60,000 ms.
3. **Post-stage:** event marker offset greater than 60,000 ms.

It then separately prints up to 40 measured-stage event
markers. These are **event lines**, not necessarily unique
garbage collections or full pause intervals; the JVM
log may have several lines per collection.

This small analytical improvement does **not** require
rerunning the load test: we can reuse the complete existing
JVM log, request CSV and Maven log.

## 5. Safe offline reanalysis on macOS

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger

BASE="$ROOT/target/http-load-lab/20261110T112042Z-57990-correlated"

python3 "$ROOT/ops/benchmarks/http/analyze-correlated-diagnostics.py" \
  "$BASE-requests.csv" \
  "$BASE-jvm.log" \
  "$BASE-maven.log"
```

Look for:

```text
VALIDATED rows=6000 ...
JVM_EVENT_PHASE_COUNTS startup=... measured=... post_stage=...
MEASURED-STAGE GC/SAFEPOINT LOG LINES ...
slow_requests_over50ms=0
```

Those stage-specific counts have **not** yet been supplied,
so their values are not reported in this chapter.

## 6. What is established, and what is not?

**Established:** the Java test, 6,000-row evidence export,
forked-JVM PID-specific logging and offline validation all
worked. This measured run did not reproduce the earlier
large tail-latency bursts.

**Not established:** the root cause of the earlier spikes,
whether a specific GC event explains a request delay,
or performance behavior over long production timeframes.

Do not tune Hikari, GC algorithms or thread counts based
on the single earlier abnormal 60-second run.

## 7. Interview checkpoint

**Q: Why isn't a successful Maven test enough?**
Because JVM event logging and artifact completeness
are separate verification gates. We verified the PID and
the actual generated file.

**Q: What is the significance of a negative JVM offset?**
It means the log marker occurred before the beginning
of the measured load stage, even if it appears in the same
JVM log.

**Q: Why distinguish log lines from GC collections?**
One garbage collection can produce several log lines
and safepoint events. Counting lines does not count
independent pauses.

**Q: Does no request over 50 ms prove the JVM is perfect?**
No. It tells us only that the 6,000 measured calls in this
one synthetic run did not cross that threshold.

**Q: What should happen before a new tuning change?**
Compare multiple controlled runs, ensure enough representative
samples, and correlate actual client and JVM events by
their verified clocks before selecting a hypothesis.
