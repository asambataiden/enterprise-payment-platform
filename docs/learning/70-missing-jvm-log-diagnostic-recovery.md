# Chapter 70 — The HTTP Test Passed but the JVM Log Was Missing

> **Milestone 22 diagnostic recovery, 2026-11-10.** The
> developer executed the correlated diagnostic runner against
> `89058a0517a5f1854f86254826bb912a138ff9e9`.
> This chapter records a **real evidence-pipeline failure**, not an
> application failure or a confirmed GC diagnosis. Payments remain
> simulated and localhost only.

## 1. What happened?

The opt-in 100 RPS / 60-second stage used 128 seeded payment
records for each of four synthetic merchants. All **6,000**
measured HTTP calls succeeded (4,800 GET and 1,200 POST).
Maven reported one test, zero failures, zero errors and
`BUILD SUCCESS`.

The client output also confirmed **6,000 CSV samples**
with real monotonic start and end offsets, plus:

```text
MIXED_HTTP_LAB stage_start_jvm_uptime_ms=14604
```

However, *after* Maven completed, the shell runner reported:

```text
Missing GC or CSV evidence
```

Thus the **HTTP correctness/performance stage passed** but
the **Milestone 22 diagnostic evidence gate failed**.
We cannot correlate request latency with JVM GC/safepoint
events without the corresponding JVM log.

## 2. The results we can and cannot use

| Observation | Actual local result |
| --- | ---: |
| HTTP 200 GET | 4,800 |
| HTTP 201 POST | 1,200 |
| Unexpected HTTP statuses | 0 |
| Elapsed, measured | 59.995 s |
| Completed requests/sec | 100.01 |
| GET p95 / p99 | 4.362 / 5.559 ms |
| POST p95 / p99 | 6.259 / 7.647 ms |
| JVM GC collections, count only | 12 |
| Maximum sampled Hikari active | 1 |
| Sampled pending Hikari | 0 |
| Max sampled PostgreSQL waits | 1 |
| Client max outstanding | 2 |
| Max client schedule lag | 2.955 ms |

The earlier slowdown did **not visibly recur** in
these 10-second p95/p99 summaries. That does not mean
the underlying cause disappeared or that the system
will never have another tail-latency incident.

The JVM event file was not found at the runner's intended
destination. We cannot determine from the shared console
alone whether it was written elsewhere or never created
by the forked JVM.

## 3. Why a missing file can occur

The original JVM argument used a **relative file path**:

```text
-Xlog:gc*,safepoint:file=target/http-load-lab/NAME.log:uptimemillis,level,tags
```

The script changed the **Maven shell** directory to the
repository root. But the forked **Surefire JVM** is a
separate process and need not interpret relative paths
from exactly the same working directory in all environments.

The observed missing file makes relative-path resolution
one plausible explanation; it is **not a proven diagnosis**
from the terminal output alone. A JVM argument propagation
or logging configuration problem is another possibility.

## 4. What was fixed?

The runner now:
1. Creates a **unique temporary absolute path without spaces**
   beneath `/tmp` for JVM event logging.
2. Runs a tiny **`java -Xlog ... -version` preflight** to
   verify the option and log location are accepted by Java.
3. Deletes the preflight log before launching Maven so that
   an old preflight file cannot masquerade as test evidence.
4. Passes the **absolute temporary log path** to Surefire
   through the existing Maven `argLine` configuration.
5. Checks that the **forked test process actually produced a
   non-empty JVM log** before attempting analysis.
6. Copies the diagnostic log to
   `target/http-load-lab/` and preserves it on exit,
   including failed test cases where possible.

This fixes the suspected path weakness and makes failed
evidence production easier to diagnose. It still requires
a local run to validate on the developer's Mac.

## 5. Why is the extra preflight important?

A successful Maven build does not prove a separate
performance evidence file exists.

Likewise, a preflight that creates a log does not prove
the **test JVM** created it. That is why we remove the
preflight's file and require the test-run output separately.

This is an instance of a general DevSecOps practice:
**verify each promised artifact independently**, and
fail closed rather than report diagnostic success.

## 6. Safe macOS recovery commands

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" status --short --branch
git -C "$ROOT" pull --ff-only origin feat/payment-ledger

# Validate Bash syntax without running the experiment:
bash -n "$ROOT/ops/benchmarks/http/run-correlated-diagnostics-local.sh"

# Re-execute one bounded, synthetic-only isolated trial:
bash "$ROOT/ops/benchmarks/http/run-correlated-diagnostics-local.sh"
```

This creates new evidence. **Do not claim that the previous
missing JVM log has been recovered.** The prior raw CSV and
Maven test output remain valuable for the HTTP test itself.

## 7. Interview questions

**Q: Why did Maven succeed but the script fail?**
Because Maven verified the Java test; the shell script
additionally required independently generated GC and CSV
evidence. One can pass while the other fails.

**Q: Why can paths containing spaces matter for JVM args?**
A JVM option is tokenized during process launch; the
entire project path can contain whitespace. A short,
space-free absolute logging path reduces parsing and
working-directory ambiguity.

**Q: Why remove the preflight log?**
Otherwise the analyzer could accept a log written by
`java -version`, falsely claiming the Surefire test JVM
was instrumented.

**Q: Does the absence of a diagnostic file prove no GC
occurred?** No. The application's telemetry indicated
12 collections, but no usable time-correlated event file
was available.

**Q: Was the latency incident resolved?** Not proven.
It did not show up in this later run's summary, but the
original seven clusters remain unexplained.

## 8. Next evidence gate

We can mark the new path handling as **committed**.
We must not mark it **verified** until the corrected
runner produces a nonempty JVM log, an accepted CSV and
a successful offline correlation report.
