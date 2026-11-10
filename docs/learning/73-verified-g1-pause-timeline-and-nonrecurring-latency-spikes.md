# Chapter 73 — What the Verified G1 Timeline Actually Demonstrates

> Milestone 22, **verified offline JVM analysis** on November 10, 2026.
> Performance evidence originates from synthetic localhost trial
> `701dc6e469b0a1d86a07b7c514c4c8e669245966`. The phase-aware
> analyzer was run after Git update to `e41a570220c4dcefea0d31539b386ad387f85ed0`.
> No real funds or production traffic.

## 1. Starting from zero: the question

An earlier 100-RPS local test had seven short **HTTP latency clusters**
near 30–40 seconds; the slowest request took **148.288 ms**.
Was the JVM's garbage collector responsible?

To answer reliably, we must use actual **JVM GC timestamps and durations**
from the *same* run as our HTTP samples. A GC event collected on
a different day, even if short, cannot retroactively explain
the earlier burst.

## 2. What the developer verified

The phase-aware analysis of the complete, PID-matched JVM log reported:

```text
VALIDATED rows=6000 anchor_jvm_uptime_ms=4807 matching_jvm_log_lines=105
JVM_EVENT_PHASE_COUNTS startup=69 measured=36 post_stage=0
slow_requests_over50ms=0
```

These are **105 matching log lines**, not 105 distinct pauses.
The 36 measured-phase lines form **12 G1 young-collection events**
(three observed lines per event: start, completion and safepoint).

The same measured 60-second experiment completed **4,800 GETs**
and **1,200 POSTs** with the expected HTTP statuses,
at **100.01 completed requests per second**.
The measured whole-stage GET p95/p99 was **4.342/5.416 ms**,
and POST p95/p99 was **6.704/7.809 ms**.

## 3. Measured-stage GC pause chronology

| GC ID | Approx. stage-relative completion | Logged G1 pause |
| --- | ---: | ---: |
| 17 | 3.026 s | 3.252 ms |
| 18 | 7.757 s | 2.502 ms |
| 19 | 12.797 s | 3.870 ms |
| 20 | 17.895 s | 3.133 ms |
| 21 | 23.011 s | 2.822 ms |
| 22 | 28.185 s | 2.830 ms |
| 23 | 33.358 s | 3.680 ms |
| 24 | 38.596 s | 3.846 ms |
| 25 | 43.817 s | 3.273 ms |
| 26 | 49.097 s | 3.051 ms |
| 27 | 54.375 s | 2.549 ms |
| 28 | 59.686 s | 3.690 ms |

The smallest logged pause was **2.502 ms**, largest
**3.870 ms**, and sum of the 12 individual logged GC
pause durations was **38.498 ms**.
This sum is not total system suspension time or CPU time:
other safepoints, Java execution and scheduling effects
are not represented by these 12 durations alone.

**Interpretation:** GC occurred routinely during this
successfully measured experiment. No HTTP request exceeded
50 ms. The original seven slow-request clusters did
**not** recur in this run.

## 4. Why the startup events do not answer our question

The 69 startup lines had negative time offsets:
they occurred before the measured stage began.
Our first diagnostic printout displayed only the beginning
of the JVM log, creating a risk of mistakenly treating
the startup events as representative of the full run.

Grouping all log lines by **startup / measured / after**
avoids that analytical error.

## 5. What does this say about the original incident?

The earlier event had multiple affected merchants and
operations, with 37 requests >50 ms and 16 >100 ms.
That run **did not have a GC/safepoint log**. Its precise
cause therefore remains unknown.

The newest experiment demonstrated:
1. The per-PID JVM evidence pipeline is working.
2. Normal G1 pauses can be observed without major HTTP tail spikes.
3. The earlier anomalous latency is **intermittent** under the
   available repeated-local-run evidence.

It did **not** establish that GC *never* causes spikes,
that Mac host scheduling is fault-free, or that increasing
the read corpus caused the earlier anomaly.

## 6. Analyzer improvement for future runs

`ops/benchmarks/http/analyze-correlated-diagnostics.py`
now separately parses GC **completion** messages with a
logged pause duration, and prints a measured-stage summary
(count, minimum, maximum and sum) followed by one line per
recorded completion.

This is preferable to counting `gc,start`, `gc` and
`safepoint` messages all as separate pauses.
The improvement can run on the **existing three saved
local artifacts**; no new traffic is required.

The analyzer change has not been executed on the Mac
yet; the exact 12 GC durations above came from the
developer's prior phase-aware output, not the new
summary function.

## 7. Offline commands, no HTTP traffic

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger

BASE="$ROOT/target/http-load-lab/20261110T112042Z-57990-correlated"

python3 "$ROOT/ops/benchmarks/http/analyze-correlated-diagnostics.py" \
  "$BASE-requests.csv" \
  "$BASE-jvm.log" \
  "$BASE-maven.log"
```

The new output section begins with
`MEASURED_GC_PAUSE_SUMMARY`. Check that 12 distinct
completion events are printed before treating the parser
as verified.

## 8. Interview preparation

**Q: Why is the count 36 not 36 GCs?** The log includes
start, completion and safepoint messages for each
collection. Here, 36 matching measured-stage log lines
represent 12 GC events.

**Q: Does 3.8-ms GC explain a 148-ms HTTP response?**
Not by itself. The two values came from different runs.
Attributing the earlier latency to GC would require
same-run correlated evidence.

**Q: Why avoid tuning Hikari now?** Previous measurements
did not show pending connection acquisitions, and the
application currently meets the synthetic workload gate.

**Q: What should we improve next?** For a true client
versus server breakdown, consider running the workload
generator in a separate process and collecting timestamped
server request and host scheduling evidence in a controlled,
bounded experiment.

## 9. Status

- **Verified:** all 6,000 HTTP calls; valid PID-specific JVM log;
  phase counts 69/36/0; 12 recorded measured-stage G1 pauses;
  zero HTTP requests over 50 ms.
- **Committed, local verification pending:** new completion-event
  summary parser.
- **Unresolved:** the root cause of the prior transient HTTP
  latency clusters.
