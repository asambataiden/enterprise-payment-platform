# Chapter 67 — Raw Request Evidence: Localized Latency Bursts

> Evidence for the synthetic Milestone 21 **100-RPS × 60-second**
> experiment executed at Git revision
> `e3c2ef2b5744acce4b3976b86cb3a08ea4ac3355`.
> The developer ran the offline analyzer against the **6,000-row**
> CSV created 2026-11-10 at 10:42:14 UTC. Root cause **not confirmed**.

## 1. What the raw CSV analysis proved

The script `ops/benchmarks/http/analyze-raw-latency.py`
printed `VALIDATED rows=6000`, checked the GET/POST
counts and tenant split, and reported the slowest individual
synthetic requests.

| Requests | Over 20 ms | Over 50 ms | Over 100 ms |
| --- | ---: | ---: | ---: |
| GET total, 4,800 | 49 | 30 | 13 |
| POST total, 1,200 | 13 | 7 | 3 |
| **Total** | **62** | **37** | **16** |

The scheduled 20–30-second window contained **two**
over-20-ms requests, but **none** over 50 ms.
The scheduled **30–40-second window contained 60**
over-20-ms requests, **all 37** over-50-ms requests
and **all 16** over-100-ms requests.
No requests exceeded 20 ms in the 0–20 or 40–60-second
windows. The entire test still completed all
6,000 HTTP requests with expected status codes.

The slowest recorded observation was:
- sequence **3824**
- merchant index **0**
- HTTP **GET**
- latency **148.288 ms**
- scheduled-window start **30 seconds**.

Other high-latency observations included sequences
**3017–3023**, **3167–3172** and **3824–3830**,
with GET and POST requests across multiple synthetic
merchants. Those are **examples from the slowest-20 list**,
not proof that these are the only slow-request groups.

## 2. Why these findings matter (no jargon)

Imagine a supermarket checkout operates normally, then
briefly delays several shoppers at the same time.
Everyone eventually pays. Counting only completed
customers would overlook the delay.

The fact that both GET and POST requests across multiple
merchants experienced long latencies suggests examining
**shared execution resources**: the local CPU scheduler,
JVM safepoints/GC, HTTP client, network stack, and the
shared database. That is a **hypothesis**, not a diagnosis.

No sampled Hikari connection-acquisition queue or
PostgreSQL active wait was recorded. Such 250-ms
sampling can miss sub-second events, so we cannot
rule out shorter waits.

## 3. An improved offline analyzer

An additional **optional diagnostic output** now groups
over-50-ms requests into clusters in **planned dispatch
sequence**. Requests are grouped when consecutive slow
sequence numbers differ by at most five; for every group
it prints the first/last sequence, count, merchant indices,
operations and maximum latency.

This threshold and gap are a **heuristic**, not a JVM,
HTTP or database causal classifier. Sequence-derived
times are *nominal* schedule positions at 100 RPS.
They are not timestamps captured from the runtime.

The grouping is useful for locating the exact short
sections to investigate with correlated JVM/host logs
in a later experiment.

## 4. Run the improved analysis

From any folder inside the Git repository:

```bash
ROOT="$(git rev-parse --show-toplevel)"
git -C "$ROOT" pull --ff-only origin feat/payment-ledger

CSV="$ROOT/target/http-load-lab/20261110T104214Z-56749-large-corpus-100rps-60s.csv"

python3 "$ROOT/ops/benchmarks/http/analyze-raw-latency.py" "$CSV"
```

The command works **offline** with the existing CSV.
No new HTTP traffic, database, AWS resources or
payment-processing endpoints are involved.

The added section begins:

```text
SLOW-REQUEST CLUSTERS (>50ms, scheduled-sequence gap <=5)
```

Do not fabricate its group counts or exact boundaries:
they depend on the full local CSV; only the developer
has the raw file. Record the actual output after running.

## 5. Recommended next diagnostic experiment

First, inspect the cluster output. If it shows multiple
tight sequence groups, add explicitly scoped diagnostic
evidence to a new **isolated** run:

- precise monotonic request-start and end offsets in CSV;
- JVM GC/safepoint pause logs with correlatable timestamps;
- system CPU/load and thread-scheduling telemetry;
- a more complete view of Hikari acquisitions/SQL waits.

Control the scenario before comparing 8 and 128 seeded
records. Keep **100 RPS × 60 seconds** at most, and
avoid tuning Hikari without evidence.

## 6. Interview Q&A

**Why isn't zero HTTP errors enough?** Because a successful
operation may still be unacceptably slow.

**What is the difference between global and time-window
p95?** A global percentile mixes quiet and slow intervals;
a windowed percentile can reveal brief incidents.

**Why do you group sequence numbers?** To identify
clusters of slow scheduled operations and select a smaller
portion of the run for closer investigation.

**Does grouping prove a GC pause?** No. We require
time-correlated GC, host and DB evidence to support that.

**Can we call this a tenant-isolation defect?** No. Slow
observations affected more than one tenant and operation,
and the test is synthetic with a small data corpus.

## 7. Status and evidence discipline

- **Verified:** all 6,000 CSV rows structurally validated;
  62 requests >20 ms, 37 >50 ms, 16 >100 ms;
  several high-latency sequences in the 30–40-second
  scheduling window; no HTTP failure.
- **Implemented but not locally reverified:** the new
  sequence-cluster printout.
- **Unknown:** the cause of the slow bursts and whether
  they recur under controlled matched conditions.

The incident remains **open**. Do not mark a suspected
cause as confirmed based solely on this CSV.
