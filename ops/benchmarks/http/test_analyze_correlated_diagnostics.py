#!/usr/bin/env python3
"""Regression tests for the offline JVM log parser; no Docker/network required."""
import importlib.util
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


if __name__ == "__main__":
    unittest.main()
