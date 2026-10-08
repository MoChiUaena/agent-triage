"""Safepoint evidence must stay numeric, bounded, and aligned with an owned failure."""
import unittest

from resource_safepoints import parse_safepoint_line, failure_uptime_ms, safepoint_diagnostics, safepoint_format_counts


def event(uptime, total, operation="G1PauseCleanup", colon=":"):
    return (f'[{uptime}ms][info][safepoint] Safepoint "{operation}", Time since last: 1000000 ns, '
        f'Reaching safepoint: 100000 ns, Cleanup: 10000 ns, At safepoint: {total - 110000} ns, '
        f'Total{colon} {total} ns')


class SafepointEvidenceTest(unittest.TestCase):
    def test_parses_both_jdk21_total_spellings_without_exporting_operation_names(self):
        first = parse_safepoint_line(event(14600, 1_600_000_000))
        second = parse_safepoint_line(event(15100, 1_200_000, colon=""))
        self.assertEqual((first["uptimeMs"], first["totalNs"]), (14600, 1_600_000_000))
        self.assertEqual((second["uptimeMs"], second["totalNs"]), (15100, 1_200_000))
        variant = event(15200, 1_200_000).replace("Time since last: 1000000 ns, ", "VM metadata: 1000000 ns, ")
        self.assertEqual(parse_safepoint_line(variant)["totalNs"], 1_200_000)
        self.assertIsNone(parse_safepoint_line("[15200ms][info][safepoint] no numeric durations"))

    def test_reads_failure_uptime_only_from_owned_unexpected_http_line(self):
        text = ("unrelated jvmUptimeMs=1\n"
            "RESOURCE_UNEXPECTED_HTTP_FAILURE method=PUT factory=simple mode=normal elapsedMs=2028.0 "
            "typedTimeout=true jvmUptimeMs=15000\n")
        self.assertEqual(failure_uptime_ms(text), 15000)
        self.assertEqual(failure_uptime_ms(
            'RESOURCE_UNEXPECTED_DRIVER_RESPONSE scenario=5 status=200 expectedStatus=504 jvmUptimeMs=16000\n'), 16000)
        self.assertEqual(failure_uptime_ms(
            'RESOURCE_FAILURE_AT jvmUptimeMs=17000 wallClockMs=1760000000789\n'), 17000)
        self.assertIsNone(failure_uptime_ms("no owned failure jvmUptimeMs=15000"))

    def test_summarizes_nearby_pause_without_exposing_raw_vm_text(self):
        lines = [event(9000, 1_800_000_000, "PRIVATE_MARKER"), event(14600, 1_600_000_000),
                 event(15100, 1_200_000, colon="")]
        rendered = safepoint_diagnostics(lines, 15000)
        self.assertIn("events=3", rendered)
        self.assertIn("peakTotalMs=1800.000", rendered)
        self.assertIn("nearEvents=2", rendered)
        self.assertIn("nearPeakTotalMs=1600.000", rendered)
        self.assertEqual(rendered.count("kind=near"), 2)
        self.assertNotIn("PRIVATE_MARKER", rendered)
        self.assertNotIn("G1PauseCleanup", rendered)

    def test_limits_nearby_lines_and_distinguishes_unreported_time(self):
        lines = [event(14000 + index * 40, 1_000_000 + index * 100_000) for index in range(20)]
        rendered = safepoint_diagnostics(lines, 15000)
        self.assertIn("nearEvents=20", rendered)
        self.assertEqual(rendered.count("kind=near"), 8)
        without_time = safepoint_diagnostics(lines, None)
        self.assertIn("failureUptimeMs=unreported", without_time)
        self.assertIn("nearEvents=unreported", without_time)
        self.assertEqual(without_time.count("kind=near"), 0)

    def test_empty_or_invalid_log_is_not_misreported_as_zero_pause(self):
        rendered = safepoint_diagnostics(["Authorization=private", "[10ms][info][safepoint] truncated"], 15000)
        self.assertIn("kind=unavailable", rendered)
        self.assertNotIn("Authorization", rendered)

    def test_format_probe_reports_only_structural_counts_without_raw_vm_text(self):
        counts = safepoint_format_counts([event(14600, 1_600_000_000, "PRIVATE_MARKER"),
            "[14.7s][info][safepoint] unrecognized", "Authorization=private"])
        for field in ("lines=3", "uptimeMs=1", "uptimeSeconds=1", "safepointTag=2",
                      "totalNs=1", "reach=1", "cleanup=1", "at=1", "parsed=1"):
            self.assertIn(field, counts)
        self.assertNotIn("PRIVATE_MARKER", counts)
        self.assertNotIn("Authorization", counts)


if __name__ == "__main__":
    unittest.main()
