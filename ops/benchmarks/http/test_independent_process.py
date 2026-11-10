#!/usr/bin/env python3
"""Milestone 24 offline contract tests; no HTTP traffic, Docker or secrets."""
import contextlib
import csv
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).parent


def load(name):
    spec = importlib.util.spec_from_file_location(
        name.replace("-", "_"), HERE / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CLIENT = load("independent-client-local.py")
ANALYZER = load("analyze-independent-process.py")


class IndependentClientContractTests(unittest.TestCase):
    def test_tenant_distribution_80_20(self):
        counts = {(merchant, op): 0
                  for merchant in range(4) for op in ("GET", "POST")}
        for n in range(1, 6001):
            merchant = CLIENT.tenant_index(n)
            op = "POST" if n % 5 == 0 else "GET"
            counts[(merchant, op)] += 1
        for merchant, (reads, writes) in ANALYZER.TENANTS.items():
            self.assertEqual(reads, counts[(merchant, "GET")])
            self.assertEqual(writes, counts[(merchant, "POST")])

    def test_refuses_external_http_destination(self):
        urls = ["http://127.0.0.1:18080/api/v1/payments/p" for _ in range(128)]
        payload = ("18080\n" + "run-1\n128\n"
                   + "testjwt\n" * 4
                   + ("\t".join(urls) + "\n") * 3
                   + ("\t".join(urls[:-1] + ["http://example.org/x"]) + "\n"))
        with self.assertRaisesRegex(ValueError, "Non-loopback|non-loopback"):
            CLIENT.read_config(io.StringIO(payload))


class IndependentPairingTests(unittest.TestCase):
    @staticmethod
    def fixtures(folder):
        client = Path(folder) / "client.csv"
        server = Path(folder) / "server.csv"
        with client.open("w", newline="", encoding="utf-8") as a, \
                server.open("w", newline="", encoding="utf-8") as b:
            w1, w2 = csv.writer(a), csv.writer(b)
            w1.writerow(ANALYZER.CLIENT)
            w2.writerow(ANALYZER.SERVER)
            for n in range(1, 6001):
                merchant = CLIENT.tenant_index(n)
                op = "POST" if n % 5 == 0 else "GET"
                dur = 6 if op == "POST" else 4
                # Clock domains deliberately have unrelated origins!
                w1.writerow([n, merchant, op, 201 if op == "POST" else 200,
                             dur, n * 10, n * 10 + dur])
                w2.writerow([n, n * 10 + 900000, dur - 1])
        return client, server

    def test_independent_clocks_are_not_compared(self):
        with tempfile.TemporaryDirectory() as folder:
            paths = self.fixtures(folder)
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                ANALYZER.analyze(*paths)
            report = buffer.getvalue()
            self.assertIn("INDEPENDENT_EVIDENCE VALIDATED rows=6000", report)
            self.assertIn("operation=GET count=4800", report)
            self.assertIn("operation=POST count=1200", report)
            self.assertIn("clock_domains=independent", report)

    def test_rejects_missing_server_pair(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b = self.fixtures(folder)
            rows = b.read_text(encoding="utf-8").splitlines()
            b.write_text("\n".join(rows[:-1]) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Expected 6000"):
                ANALYZER.analyze(a, b)


if __name__ == "__main__":
    unittest.main()
