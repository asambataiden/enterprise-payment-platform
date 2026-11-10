#!/usr/bin/env python3
"""Offline, synthetic only: compare measured client timing with JVM log intervals.

Not proof of GC cause: client and application share one test JVM, and
JVM uptime and nanoTime clocks are sampled near, not atomically at, the stage boundary.
"""
import argparse
import csv
import math
import re
from collections import Counter
from pathlib import Path

HEADER = ("sequence", "merchant_index", "operation", "http_status",
          "latency_ms", "window_start_second",
          "client_start_offset_ms", "client_end_offset_ms")

UPTIME = re.compile(r"MIXED_HTTP_LAB stage_start_jvm_uptime_ms=(\d+)")
JVM_EVENT = re.compile(r"^\[(\d+)ms\]\[[^]]+\]\[([^]]+)\] (.*)$")
PAUSE = re.compile(r"\bPause\b|Safepoint", re.IGNORECASE)
GC_COMPLETION = re.compile(r"^GC\((\d+)\) Pause .+? ([0-9]+(?:\.[0-9]+)?)ms$")


def gc_completion(event):
    """Extract one completed GC pause; reject start/safepoint messages.

    Unified JVM logging tags can be padded with spaces; normalize before
    checking the exact 'gc' completion tag. Multiple log lines can belong
    to one collection, so only completion messages are counted.
    """
    offset, tags, message = event
    if tags.strip() != "gc":
        return None
    match = GC_COMPLETION.match(message.strip())
    if not match:
        return None
    return offset, int(match.group(1)), float(match.group(2))


def analyze(csv_file, gc_file, mvn_file):
    maven_text = Path(mvn_file).read_text(encoding="utf-8", errors="replace")
    anchors = [int(x) for x in UPTIME.findall(maven_text)]
    if len(anchors) != 1:
        raise ValueError(f"Expected one measurement JVM uptime anchor; found {len(anchors)}")
    anchor_ms = anchors[0]
    samples = []
    counts = Counter()
    with Path(csv_file).open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if tuple(reader.fieldnames or ()) != HEADER:
            raise ValueError("Wrong diagnostic CSV header")
        for row in reader:
            seq = int(row["sequence"])
            merchant = int(row["merchant_index"])
            op = row["operation"]
            status = int(row["http_status"])
            duration = float(row["latency_ms"])
            start = float(row["client_start_offset_ms"])
            end = float(row["client_end_offset_ms"])
            window = int(row["window_start_second"])
            if (not 1 <= seq <= 6000 or merchant not in range(4)
                    or op not in ("GET", "POST")
                    or status != (200 if op == "GET" else 201)
                    or (seq % 5 == 0) != (op == "POST")
                    or window != ((seq - 1) // 1000) * 10
                    or not all(map(math.isfinite, (duration, start, end)))
                    or duration < 0 or start < 0 or end < start
                    or abs((end - start) - duration) > 0.02):
                raise ValueError(f"Invalid request timing at sequence {seq}")
            samples.append((seq, merchant, op, duration, start, end))
            counts[(merchant, op)] += 1
    if len(samples) != 6000 or len({s[0] for s in samples}) != 6000:
        raise ValueError("CSV missing or duplicate request sequences")
    expected = {0: (3360, 840), 1: (480, 120), 2: (480, 120), 3: (480, 120)}
    for merchant, (reads, writes) in expected.items():
        if counts[(merchant, "GET")] != reads or counts[(merchant, "POST")] != writes:
            raise ValueError(f"Incorrect merchant operation counts: {merchant}")

    events = []
    for line in Path(gc_file).read_text(encoding="utf-8", errors="replace").splitlines():
        m = JVM_EVENT.match(line)
        if m:
            uptime, tags, message = int(m.group(1)), m.group(2), m.group(3)
            if PAUSE.search(message) and tags.startswith(("gc", "safepoint")):
                events.append((uptime - anchor_ms, tags, message))
    if not events:
        print("NOTE: no matching GC/safepoint event lines in this log")
    print(f"VALIDATED rows=6000 anchor_jvm_uptime_ms={anchor_ms} matching_jvm_log_lines={len(events)}")
    # Classify event lines by their JVM-uptime marker rather than by
    # which first-60 lines happen to be printed. A JVM event marker may
    # represent an event ending, rather than an event starting.
    startup_events = [event for event in events if event[0] < 0]
    measured_events = [event for event in events if 0 <= event[0] <= 60_000]
    post_stage_events = [event for event in events if event[0] > 60_000]
    print(f"JVM_EVENT_PHASE_COUNTS startup={len(startup_events)}"
          f" measured={len(measured_events)} post_stage={len(post_stage_events)}")
    # GC-completion messages have a pause duration at the end of the
    # message. Count these rather than all three corresponding log lines.
    completed_pauses = []
    for event in measured_events:
        completion = gc_completion(event)
        if completion is not None:
            completed_pauses.append(completion)
    if completed_pauses:
        durations = [pause[2] for pause in completed_pauses]
        print(f"MEASURED_GC_PAUSE_SUMMARY count={len(completed_pauses)}"
              f" min_ms={min(durations):.3f} max_ms={max(durations):.3f}"
              f" sum_ms={sum(durations):.3f}")
        print("MEASURED_GC_PAUSES (completion markers; JVM uptime offsets)")
        for offset, gc_id, duration in completed_pauses:
            print(f"gc_id={gc_id} completion_offset_ms={offset}"
                  f" pause_duration_ms={duration:.3f}")
    else:
        print("MEASURED_GC_PAUSE_SUMMARY count=0 (no matching completion lines)")
    print("MEASURED-STAGE GC/SAFEPOINT LOG LINES (first 40, JVM uptime offset)")
    for offset, tags, message in measured_events[:40]:
        print(f"jvm_uptime_offset_ms={offset} tags={tags} detail={message[:180]}")
    if not measured_events:
        print("No matching JVM event markers within the measured 60-second interval.")
    print("The count is of matching log LINES, not distinct GC cycles or pauses.")

    print("OVER-50MS CLIENT REQUESTS (actual measured relative offsets)")
    slow = sorted((s for s in samples if s[3] > 50), key=lambda s: s[4])
    print(f"slow_requests_over50ms={len(slow)}")
    for seq, merchant, op, duration, start, end in slow[:40]:
        print(f"sequence={seq} merchant={merchant} op={op}"
              f" client_start_ms={start:.3f} client_end_ms={end:.3f}"
              f" latency_ms={duration:.3f}")
    print("JVM GC/SAFEPOINT LOG LINES (uptime relative to measured-stage anchor)")
    for offset, tags, message in events[:60]:
        print(f"jvm_uptime_offset_ms={offset} tags={tags} detail={message[:180]}")
    print("CAUTION: GC/safepoint log lines may mark event completion, start, or duration")
    print("depending on tags; do not treat all log lines as complete pause intervals.")
    print("The JVM clock anchor is approximate; coincidence is not a causal test.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("jvm_log", type=Path)
    parser.add_argument("maven_log", type=Path)
    args = parser.parse_args()
    try:
        analyze(args.csv_file, args.jvm_log, args.maven_log)
    except (OSError, ValueError) as error:
        parser.exit(1, f"INVALID EVIDENCE: {error}\n")
