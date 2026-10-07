"""Independent sampler gaps are numeric, bounded, and aligned conservatively."""
import unittest

from resource_host_schedule import HostSchedulingProbe, failure_wall_ms


class HostSchedulingProbeTest(unittest.TestCase):
    def test_records_external_sampler_gap_near_an_owned_http_failure(self):
        probe = HostSchedulingProbe()
        probe.tick(0, 1000)
        probe.tick(100_000_000, 1100)
        probe.tick(250_000_000, 1250)
        probe.tick(650_000_000, 1650)
        text = probe.render(1700)
        self.assertIn('ticks=3', text)
        self.assertIn('peakGapMs=300.000', text)
        self.assertIn('nearEvents=2', text)
        self.assertIn('nearPeakGapMs=300.000', text)
        self.assertIn('wallMs=1650 gapMs=300.000', text)

    def test_near_report_caps_at_eight_and_keeps_global_peak_after_ring_rollover(self):
        probe = HostSchedulingProbe()
        probe.tick(0, 1000)
        probe.tick(1_100_000_000, 2100)
        for index in range(600):
            probe.tick(1_100_000_000 + (index + 1) * 160_000_000, 2200 + index)
        text = probe.render(2500)
        self.assertIn('peakGapMs=1000.000', text)
        self.assertEqual(text.count('kind=near'), 8)
        self.assertLessEqual(len(text), 2400)

    def test_missing_failure_time_and_no_ticks_are_not_measured_zero(self):
        probe = HostSchedulingProbe()
        self.assertIn('kind=unavailable', probe.render(1700))
        probe.tick(0, 1000)
        probe.tick(100_000_000, 1100)
        text = probe.render(None)
        self.assertIn('failureWallMs=unreported', text)
        self.assertIn('nearEvents=unreported', text)
        self.assertEqual(text.count('kind=near'), 0)

    def test_reads_failure_wall_time_only_from_owned_unexpected_http_record(self):
        text = ('unrelated wallClockMs=1\n'
            'RESOURCE_UNEXPECTED_HTTP_FAILURE method=GET mode=normal '
            'elapsedMs=2200.0 jvmUptimeMs=14000 wallClockMs=1760000000123\n')
        self.assertEqual(failure_wall_ms(text), 1760000000123)
        self.assertIsNone(failure_wall_ms('other wallClockMs=1760000000123'))


if __name__ == '__main__':
    unittest.main()
