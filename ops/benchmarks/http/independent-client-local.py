#!/usr/bin/env python3
"""Independent-process localhost-only simulated payment HTTP load generator.

Configuration is passed over a pipe on stdin: port, run ID, record count,
four synthetic JWT strings, four tab-separated read-URL lists. The process
never prints or stores bearer tokens. Its monotonic clock is INDEPENDENT
of the server JVM; correlate by sequence, not absolute offsets.
"""
import concurrent.futures
import csv
import pathlib
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

RATE = 100
REQUESTS = 6000
MAX_INFLIGHT = 32
WORKERS = 8
HEADER = ("sequence", "merchant_index", "operation", "http_status",
          "latency_ms", "client_start_offset_ms", "client_end_offset_ms")


def tenant_index(n):
    bucket = ((n // 5) * 7 + (n % 5) * 3) % 10
    return 0 if bucket < 7 else bucket - 6


def read_config(source):
    port = int(source.readline().strip())
    run_id = source.readline().strip()
    records = int(source.readline().strip())
    tokens = [source.readline().strip() for _ in range(4)]
    urls = [source.readline().rstrip("\n").split("\t") for _ in range(4)]
    if not (1024 <= port <= 65535) or not run_id or records != 128:
        raise ValueError("Invalid localhost synthetic workload configuration")
    if not all(tokens) or any(len(group) != 128 for group in urls):
        raise ValueError("Missing synthetic merchant fixture or token")
    for group in urls:
        for url in group:
            parsed = urllib.parse.urlsplit(url)
            if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1"
                    or parsed.port != port
                    or not parsed.path.startswith("/api/v1/payments/")):
                raise ValueError("Refusing non-loopback or unexpected payment URL")
    return port, run_id, tokens, urls


def send(n, port, run_id, tokens, urls, origin_ns):
    merchant = tenant_index(n)
    write = n % 5 == 0
    if write:
        url = f"http://127.0.0.1:{port}/api/v1/payments"
        data = (f'{{"amount":1.25,"currency":"EUR",'
                f'"merchantReference":"mixed-{n}"}}').encode("utf-8")
    else:
        url = urls[merchant][(n // 10) % 128]
        data = None
    headers = {"Authorization": "Bearer " + tokens[merchant],
               "X-Lab-Sequence": str(n)}
    if write:
        headers["Idempotency-Key"] = f"mixed-{run_id}-{n}"
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        url, data=data, headers=headers, method="POST" if write else "GET")
    begin = time.perf_counter_ns()
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            status = response.status
            response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
    finish = time.perf_counter_ns()
    return (n, merchant, "POST" if write else "GET", status,
            (finish - begin) / 1e6,
            (begin - origin_ns) / 1e6, (finish - origin_ns) / 1e6)


def execute(path, source):
    port, run_id, tokens, urls = read_config(source)
    output = pathlib.Path(path).resolve()
    if output.suffix != ".csv" or output.parent.name != "http-load-lab":
        raise ValueError("Output must be a CSV in http-load-lab")
    permits = threading.BoundedSemaphore(MAX_INFLIGHT)
    tasks = []
    max_inflight = 0
    active = 0
    active_lock = threading.Lock()
    max_lag_ns = 0
    origin = time.perf_counter_ns()

    def limited(n):
        nonlocal active
        try:
            return send(n, port, run_id, tokens, urls, origin)
        finally:
            with active_lock:
                active -= 1
            permits.release()

    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as workers:
        for n in range(1, REQUESTS + 1):
            due = origin + (n - 1) * 1_000_000_000 // RATE
            delay = due - time.perf_counter_ns()
            if delay > 0:
                time.sleep(delay / 1e9)
            max_lag_ns = max(max_lag_ns, time.perf_counter_ns() - due)
            if not permits.acquire(blocking=False):
                raise RuntimeError("Backpressure: more than 32 outstanding HTTP calls")
            with active_lock:
                active += 1
                max_inflight = max(max_inflight, active)
            try:
                tasks.append(workers.submit(limited, n))
            except Exception:
                with active_lock:
                    active -= 1
                permits.release()
                raise
        samples = [task.result(timeout=10) for task in tasks]
    elapsed = (time.perf_counter_ns() - origin) / 1e9
    if len(samples) != REQUESTS:
        raise ValueError("Incomplete measured request results")
    samples.sort(key=lambda row: row[0])
    if any(row[3] != (201 if row[2] == "POST" else 200) for row in samples):
        raise ValueError("Unexpected HTTP response status in synthetic workload")
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(HEADER)
        writer.writerows(samples)
    print(f"INDEPENDENT_CLIENT rows={len(samples)} achieved_rps={len(samples)/elapsed:.2f}"
          f" elapsed_sec={elapsed:.3f} max_outstanding={max_inflight}"
          f" max_schedule_lag_ms={max_lag_ns/1e6:.3f}"
          f" slow_over50ms={sum(row[4] > 50 for row in samples)}",
          flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: independent-client-local.py OUTPUT_CSV")
    try:
        execute(sys.argv[1], sys.stdin)
    except Exception as error:
        # Error messages contain no secrets. Do not print source/config.
        raise SystemExit(f"INDEPENDENT_CLIENT_FAILED: {type(error).__name__}: {error}")
