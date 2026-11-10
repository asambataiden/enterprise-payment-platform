#!/usr/bin/env python3
"""Offline synthetic HTTP-latency CSV investigation, stdlib only.

Usage:
  python3 ops/benchmarks/http/analyze-raw-latency.py target/http-load-lab/RUN.csv

No network connections, no payment details, no external dependencies.
"""
import argparse
import csv
from collections import Counter, defaultdict
from pathlib import Path
import math

COLUMNS = ("sequence", "merchant_index", "operation", "http_status",
           "latency_ms", "window_start_second")
EXPECTED_ROWS = 6000
EXPECTED_OPS = {"GET": 4800, "POST": 1200}
EXPECTED_TENANTS = {
    0: {"GET": 3360, "POST": 840},
    1: {"GET": 480, "POST": 120},
    2: {"GET": 480, "POST": 120},
    3: {"GET": 480, "POST": 120},
}


def percentile(values, portion):
    sorted_values = sorted(values)
    if not sorted_values:
        raise ValueError("Cannot compute a percentile from no samples")
    return sorted_values[max(0, math.ceil(len(sorted_values) * portion) - 1)]


def analyze(path):
    samples = []
    seen = set()
    with Path(path).open(newline="", encoding="utf-8") as handle:
        rows = csv.DictReader(handle)
        if tuple(rows.fieldnames or ()) != COLUMNS:
            raise ValueError("Unexpected CSV header; expected: " + ",".join(COLUMNS))
        for record in rows:
            try:
                sequence = int(record["sequence"])
                merchant = int(record["merchant_index"])
                op = record["operation"]
                status = int(record["http_status"])
                latency = float(record["latency_ms"])
                window = int(record["window_start_second"])
            except (ValueError, KeyError) as exc:
                raise ValueError("Invalid request row") from exc
            if (not 1 <= sequence <= EXPECTED_ROWS or sequence in seen
                    or merchant not in EXPECTED_TENANTS
                    or op not in EXPECTED_OPS
                    or status != (200 if op == "GET" else 201)
                    or (sequence % 5 == 0) != (op == "POST")
                    or window != ((sequence - 1) // 1000) * 10
                    or not math.isfinite(latency) or latency < 0):
                raise ValueError("Invalid or duplicate measurement at sequence " + str(sequence))
            seen.add(sequence)
            samples.append((sequence, merchant, op, latency, window))
    if len(samples) != EXPECTED_ROWS:
        raise ValueError(f"Expected {EXPECTED_ROWS} rows; received {len(samples)}")

    counts = defaultdict(Counter)
    windows = defaultdict(list)
    for seq, merchant, op, ms, window in samples:
        counts[merchant][op] += 1
        windows[(window, op)].append(ms)
    for merchant, counts_expected in EXPECTED_TENANTS.items():
        if dict(counts[merchant]) != counts_expected:
            raise ValueError(f"Merchant {merchant} operation counts incorrect: {dict(counts[merchant])}")
    print(f"VALIDATED rows={len(samples)} operations={EXPECTED_OPS} tenant_distribution=PASS")
    print("WINDOW (scheduled request window, not completion timestamp)")
    for window in range(0, 60, 10):
        for op, expected in (("GET", 800), ("POST", 200)):
            values = windows[(window, op)]
            if len(values) != expected:
                raise ValueError(f"Window {window} {op} has {len(values)} samples; expected {expected}")
            print(f"start={window:02d}s op={op:4s} count={len(values):4d}"
                  f" p95_ms={percentile(values, .95):.3f}"
                  f" p99_ms={percentile(values, .99):.3f}"
                  f" over20ms={sum(x > 20 for x in values)}"
                  f" over50ms={sum(x > 50 for x in values)}"
                  f" over100ms={sum(x > 100 for x in values)}")
    # Group adjacent scheduled requests with latency > 50 ms. We allow
    # gaps of up to five sequence IDs so GET/POST interleaving doesn't
    # fragment a short burst into dozens of one-request clusters.
    # This is a heuristic grouping by planned sequence, not an exact
    # wall-clock pause or a causality classifier.
    slow = sorted((s for s in samples if s[3] > 50), key=lambda s: s[0])
    groups = []
    for sample in slow:
        if not groups or sample[0] - groups[-1][-1][0] > 5:
            groups.append([sample])
        else:
            groups[-1].append(sample)
    print("SLOW-REQUEST CLUSTERS (>50ms, scheduled-sequence gap <=5)")
    for group in groups:
        merchants = sorted({s[1] for s in group})
        operations = sorted({s[2] for s in group})
        print(f"sequence_first={group[0][0]} sequence_last={group[-1][0]}"
              f" count={len(group)}"
              f" scheduled_start_sec={(group[0][0]-1)/100:.2f}"
              f" scheduled_end_sec={(group[-1][0]-1)/100:.2f}"
              f" max_latency_ms={max(s[3] for s in group):.3f}"
              f" merchants={merchants} operations={operations}")
    print("Cluster timestamps are nominal dispatch times, not measured wall-clock timestamps.")
    print("SLOWEST 20 REQUESTS (synthetic fields only)")
    for seq, merchant, op, ms, window in sorted(samples, key=lambda s: s[3], reverse=True)[:20]:
        print(f"sequence={seq} merchant={merchant} op={op}"
              f" window_start_sec={window} latency_ms={ms:.3f}")
    print("NOTE: the CSV lacks host/GC/DB event timestamps; it cannot attribute root cause.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path, help="Synthetic 100-RPS 60s CSV")
    args = parser.parse_args()
    try:
        analyze(args.csv_file)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"INVALID EVIDENCE: {exc}\n")
