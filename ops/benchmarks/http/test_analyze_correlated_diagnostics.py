#!/usr/bin/env python3
"""Regression tests for the offline JVM log parser; no Docker/network required."""
import importlib.util
import contextlib
import csv
import io
import tempfile
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("analyze-correlated-diagnostics.py")
SPEC = importlib.util.spec_from_file_location("correlated_diagnostics", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GcCompletionParsingTest(unittest.TestCase):
    def test_actual_padded_gc_completion_tag(self):
        # Mirrors the real logged GC(17) completion in the 2026-11-10 lab.
        event = (3026, "gc           ",
                 "GC(17) Pause Young (Normal) (G1 Evacuation Pause) "
                 "135M->57M(176M) 3.252ms")
        self.assertEqual((3026, 17, 3.252), MODULE.gc_completion(event))

    def test_start_and_safepoint_not_double_counted(self):
        events = [
            (3022, "gc,start     ",
             "GC(17) Pause Young (Normal) (G1 Evacuation Pause)"),
            (3026, "gc           ",
             "GC(17) Pause Young (Normal) (G1 Evacuation Pause) "
             "135M->57M(176M) 3.252ms"),
            (3026, "safepoint    ",
             'Safepoint "G1CollectForAllocation", Total: 3299792 ns'),
        ]
        completed = [x for x in map(MODULE.gc_completion, events) if x is not None]
        self.assertEqual([(3026, 17, 3.252)], completed)

    def test_twelve_known_measured_pause_durations(self):
        # Duration values are from the developer's existing JVM output,
        # not assumed data from a new run.
        durations = [3.252, 2.502, 3.870, 3.133, 2.822, 2.830,
                     3.680, 3.846, 3.273, 3.051, 2.549, 3.690]
        events = [
            (3026 + i * 100, "gc           ",
             f"GC({17+i}) Pause Young (Normal) (G1 Evacuation Pause) "
             f"135M->57M(176M) {duration:.3f}ms")
            for i, duration in enumerate(durations)
        ]
        completed = [MODULE.gc_completion(event) for event in events]
        self.assertTrue(all(x is not None for x in completed))
        self.assertEqual(12, len(completed))
        self.assertAlmostEqual(38.498, sum(x[2] for x in completed), places=3)
        self.assertEqual(2.502, min(x[2] for x in completed))
        self.assertEqual(3.870, max(x[2] for x in completed))


class ClientServerEvidenceTest(unittest.TestCase):
    def _fixture(self, directory, server):
        root = Path(directory)
        csv_path = root / "synthetic.csv"
        jvm_path = root / "synthetic-jvm.log"
        maven_path = root / "synthetic-maven.log"
        header = MODULE.HEADER + (MODULE.SERVER_COLUMNS if server else ())
        merchant_get = [0] * 3360 + [1] * 480 + [2] * 480 + [3] * 480
        merchant_post = [0] * 840 + [1] * 120 + [2] * 120 + [3] * 120
        with csv_path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(header)
            gi = wi = 0
            for n in range(1, 6001):
                write = n % 5 == 0
                merchant = merchant_post[wi] if write else merchant_get[gi]
                if write:
                    wi += 1
                else:
                    gi += 1
                duration = 5 if write else 4
                start = n * 10
                row = [n, merchant, "POST" if write else "GET",
                       201 if write else 200, duration,
                       ((n - 1) // 1000) * 10, start, start + duration]
                if server:
                    row.extend([start + 1, duration - 2])
                writer.writerow(row)
        jvm_path.write_text(
            "[5010ms][info][gc           ] GC(1) Pause Young (Normal) "
            "(G1 Evacuation Pause) 130M->60M(176M) 3.200ms\\n",
            encoding="utf-8")
        maven_path.write_text(
            "MIXED_HTTP_LAB stage_start_jvm_uptime_ms=5000 "
            "timing_basis=monotonic_client_offsets\\n", encoding="utf-8")
        return csv_path, jvm_path, maven_path

    def test_backward_compatible_client_only_csv(self):
        with tempfile.TemporaryDirectory() as directory:
            files = self._fixture(directory, server=False)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                MODULE.analyze(*files)
            self.assertIn("CLIENT_VS_SERVER_TIMING unavailable", output.getvalue())
            self.assertIn("VALIDATED rows=6000", output.getvalue())

    def test_client_server_csv_is_validated(self):
        with tempfile.TemporaryDirectory() as directory:
            files = self._fixture(directory, server=True)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                MODULE.analyze(*files)
            self.assertIn("operation=GET samples=4800", output.getvalue())
            self.assertIn("operation=POST samples=1200", output.getvalue())
            self.assertIn("negative_client_minus_server_residuals=0", output.getvalue())
            self.assertIn("MEASURED_GC_PAUSE_SUMMARY count=1", output.getvalue())

    def test_negative_server_duration_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            files = self._fixture(directory, server=True)
            path = files[0]
            rows = path.read_text(encoding="utf-8").splitlines()
            pieces = rows[1].split(",")
            pieces[-1] = "-2"
            rows[1] = ",".join(pieces)
            path.write_text("\\n".join(rows) + "\\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Invalid server timing"):
                MODULE.analyze(*files)



if __name__ == "__main__":
    unittest.main()
