"""Verify the bounded cost probe rejects missed cadence and missing private samples."""
import unittest
from unittest import mock

import petclinic_multi_cost as cost


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class CostProbeTest(unittest.TestCase):
    def test_exact_cadence_and_numeric_private_samples(self):
        clock = FakeClock()
        requests = []
        with mock.patch.object(cost.time, "monotonic", clock.monotonic), \
                mock.patch.object(cost.time, "sleep", clock.sleep), \
                mock.patch.object(cost, "process_memory", lambda pid: {"rssBytes": 400_000_000, "privateBytes": None}), \
                mock.patch.object(cost, "parse_linux_rollup", lambda text: {
                    "rollupPrivateCleanBytes": 10_000_000, "rollupPrivateDirtyBytes": 30_000_000}), \
                mock.patch.object(cost.Path, "read_text", lambda self, encoding: "numeric-only"):
            def invoke(index):
                clock.now += .1
            summary, samples = cost.measure(60, 123, invoke, on_request=requests.append)
        self.assertEqual(summary["completedRequests"], 120)
        self.assertEqual(summary["observedRps"], 2.0)
        self.assertEqual(summary["baselinePrivateBytes"], 40_000_000)
        self.assertLess(summary["maxStartLagMs"], 250)
        self.assertEqual(len(requests), 120)
        self.assertEqual(requests[-1]["scheduledElapsedSeconds"], 59.5)
        self.assertTrue(all(row["privateBytes"] == 40_000_000 for row in samples))

    def test_slow_requests_fail_before_publishing_a_lower_rate_as_two_rps(self):
        clock = FakeClock()
        with mock.patch.object(cost.time, "monotonic", clock.monotonic), \
                mock.patch.object(cost.time, "sleep", clock.sleep), \
                mock.patch.object(cost, "process_memory", lambda pid: {"rssBytes": 400_000_000, "privateBytes": 100_000_000}):
            def invoke(index):
                clock.now += .6
            with self.assertRaisesRegex(ValueError, "cadence missed"):
                cost.measure(60, 123, invoke)

    def test_hour_uses_the_same_exact_two_per_second_schedule(self):
        clock = FakeClock()
        with mock.patch.object(cost.time, "monotonic", clock.monotonic), \
                mock.patch.object(cost.time, "sleep", clock.sleep), \
                mock.patch.object(cost, "process_memory", lambda pid: {"rssBytes": 400_000_000, "privateBytes": 100_000_000}):
            def invoke(index):
                clock.now += .1
            summary, samples = cost.measure(3600, 123, invoke)
        self.assertEqual(summary["completedRequests"], 7200)
        self.assertEqual(summary["warmupSeconds"], 720)
        self.assertGreaterEqual(len(samples), 240)


if __name__ == "__main__":
    unittest.main()
