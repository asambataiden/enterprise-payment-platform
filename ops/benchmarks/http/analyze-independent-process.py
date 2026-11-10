#!/usr/bin/env python3
"""Validate separate Python-client and server-JVM evidence without clock subtraction."""
import argparse
import csv
import math
from collections import Counter
from pathlib import Path

CLIENT = ("sequence", "merchant_index", "operation", "http_status",
          "latency_ms", "client_start_offset_ms", "client_end_offset_ms")
SERVER = ("sequence", "server_start_offset_ms", "server_elapsed_ms")
TENANTS = {0: (3360, 840), 1: (480, 120), 2: (480, 120), 3: (480, 120)}


def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def read_csv(path, expected):
    with Path(path).open(newline="", encoding="utf-8") as file:
        reader = csv.DictReader(file)
        if tuple(reader.fieldnames or ()) != expected:
            raise ValueError(f"Wrong CSV columns: {path}")
        records = list(reader)
    if len(records) != 6000:
        raise ValueError(f"Expected 6000 records in {path}, got {len(records)}")
    return records


def analyze(client_path, server_path):
    clients = {}
    counts = Counter()
    for row in read_csv(client_path, CLIENT):
        n = int(row["sequence"])
        merchant = int(row["merchant_index"])
        op = row["operation"]
        status = int(row["http_status"])
        duration = float(row["latency_ms"])
        begin = float(row["client_start_offset_ms"])
        end = float(row["client_end_offset_ms"])
        bucket = ((n // 5) * 7 + (n % 5) * 3) % 10
        expected_merchant = 0 if bucket < 7 else bucket - 6
        if (not 1 <= n <= 6000 or n in clients
                or merchant != expected_merchant
                or op != ("POST" if n % 5 == 0 else "GET")
                or status != (201 if op == "POST" else 200)
                or not all(math.isfinite(x) for x in (duration, begin, end))
                or begin < 0 or end < begin or duration < 0
                or abs(end - begin - duration) > 0.02):
            raise ValueError(f"Invalid or duplicated independent client request {n}")
        clients[n] = (merchant, op, duration, begin, end)
        counts[(merchant, op)] += 1
    for merchant, (reads, writes) in TENANTS.items():
        if (counts[(merchant, "GET")], counts[(merchant, "POST")]) != (reads, writes):
            raise ValueError(f"Invalid synthetic tenant distribution {merchant}")

    servers = {}
    for row in read_csv(server_path, SERVER):
        n = int(row["sequence"])
        offset = float(row["server_start_offset_ms"])
        elapsed = float(row["server_elapsed_ms"])
        if (not 1 <= n <= 6000 or n in servers
                or not all(math.isfinite(x) for x in (offset, elapsed))
                or offset < 0 or elapsed < 0):
            raise ValueError(f"Invalid or duplicate server sample {n}")
        servers[n] = (offset, elapsed)
    if clients.keys() != servers.keys():
        raise ValueError("Client/server sequences do not pair exactly")

    print("INDEPENDENT_EVIDENCE VALIDATED rows=6000 merchants=4"
          " processes=separate clock_domains=independent")
    for op in ("GET", "POST"):
        ids = [n for n in clients if clients[n][1] == op]
        client_ms = [clients[n][2] for n in ids]
        server_ms = [servers[n][1] for n in ids]
        residual = [clients[n][2] - servers[n][1] for n in ids]
        print(f"operation={op} count={len(ids)}"
              f" client_p95_ms={percentile(client_ms, .95):.3f}"
              f" server_p95_ms={percentile(server_ms, .95):.3f}"
              f" residual_p95_ms={percentile(residual, .95):.3f}"
              f" over50ms={sum(x > 50 for x in client_ms)}"
              f" negative_residuals={sum(x < 0 for x in residual)}")
    print("Offsets are from different monotonic clocks and MUST NOT be compared.")
    print("Client/server durations can be paired; residual is not network-only timing.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("client_csv")
    parser.add_argument("server_csv")
    args = parser.parse_args()
    try:
        analyze(args.client_csv, args.server_csv)
    except (OSError, ValueError) as error:
        parser.exit(1, f"INVALID INDEPENDENT EVIDENCE: {error}\n")
