# Chapter 74 — A Good JVM Log Was Mistaken for Zero GC Pauses

> **Milestone 22 follow-up: evidence-pipeline regression, November 10, 2026.**
> The developer reanalyzed the same saved, synthetic 6,000-request
> experiment (test commit `701dc6e469b0a1d86a07b7c514c4c8e669245966`)
> using the new pause-summary parser from commit `0c7e8764`.
> No new HTTP workload was generated.

## 1. What the console actually showed

```text
VALIDATED rows=6000 anchor_jvm_uptime_ms=4807 matching_jvm_log_lines=105
JVM_EVENT_PHASE_COUNTS startup=69 measured=36 post_stage=0
MEASURED_GC_PAUSE_SUMMARY count=0 (no matching completion lines)
```

But the same output contained many measured-stage completion lines,
including:

```text
jvm_uptime_offset_ms=3026 tags=gc           detail=GC(17) Pause Young (Normal) (G1 Evacuation Pause) 135M->57M(176M) 3.252ms
```

The apparent zero count was therefore **not evidence of zero garbage
collections**. It contradicted the event lines directly below it.

## 2. The defect and corrective change

The parser originally required `tags == "gc"`. The real log
field was padded with spaces: `"gc           "`. An exact
string comparison against `"gc"` failed even though the
completion message and duration were present and valid.

The implementation now normalizes the tag and message:

```python
if tags.strip() != "gc":
    return None
match = GC_COMPLETION.match(message.strip())
```

It parses **only completion records**, not `gc,start` or
`safepoint` records, so one G1 collection is not
mistaken for three different pauses.

The extraction logic lives in the small testable
`gc_completion(event)` helper of
`ops/benchmarks/http/analyze-correlated-diagnostics.py`.

## 3. Regression tests

The standard-library-only test module,
`ops/benchmarks/http/test_analyze_correlated_diagnostics.py`,
uses a captured-format GC completion line with padded tags,
rejects start/safepoint lines, and checks a twelve-duration
fixture against the known values:

| Expected metric | Value |
| --- | ---: |
| Completed G1 pauses | 12 |
| Minimum pause | 2.502 ms |
| Maximum pause | 3.870 ms |
| Summed logged pause durations | 38.498 ms |
| Measured-stage GC/safepoint lines | 36 |
| Requests slower than 50 ms | 0 |

These numerical values come from the previously supplied
JVM and HTTP observations. The **new parser's output still
needs validation using the local saved artifacts**.

## 4. Reproduce safely on macOS

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger

# Fast parser regression tests. No Docker and no HTTP calls.
python3 "$ROOT/ops/benchmarks/http/test_analyze_correlated_diagnostics.py"

# Reuse saved evidence. Do not rerun the 60-second load stage.
BASE="$ROOT/target/http-load-lab/20261110T112042Z-57990-correlated"
python3 "$ROOT/ops/benchmarks/http/analyze-correlated-diagnostics.py" \
  "$BASE-requests.csv" \
  "$BASE-jvm.log" \
  "$BASE-maven.log"
```

The desired line is:

```text
MEASURED_GC_PAUSE_SUMMARY count=12 min_ms=2.502 max_ms=3.870 sum_ms=38.498
```

**That line is an expected gate, not a reported result from the
fixed parser until the developer runs it.**

## 5. Practical engineering lessons

A successful CSV validation is not proof that every downstream
summary is correct. A parser can accept a complete dataset and
silently misclassify the records.

Production-style observability needs both **input integrity**
and **semantic accuracy**. Comparing a calculated summary
against its supporting raw event lines is a valuable invariant.

In an interview, explain this as an observability bug:
**the system emitted GC evidence correctly; the reader
misinterpreted it because of whitespace-sensitive tag parsing**.

The original intermittent HTTP latency incident remains
unexplained. We should not tune garbage collection or HikariCP
based on either the false-zero result or a single other run.
