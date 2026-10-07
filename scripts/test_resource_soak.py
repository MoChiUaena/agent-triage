"""Guard diagnostic parsing, incomplete coverage and growth failures independently of workload code."""
import unittest
import io
import tempfile
from pathlib import Path
from resource_soak import parse_native_memory, parse_heap, summarize, parse_workload_result, failure_diagnostics, source_provenance, emit_failure_log, emit_bounded_failure_evidence, http_timing_arguments, native_trim_arguments, safepoint_jvm_option
from resource_host_schedule import HostSchedulingProbe

NMT = """1234:
Native Memory Tracking:
Total: reserved=180000KB, committed=140000KB
- Java Heap (reserved=131072KB, committed=98304KB)
- Thread (reserved=5000KB, committed=1000KB)
    (thread #25)
"""

def sample(elapsed, phase="running", native=42696704, rss=170000000, pid=1234):
    return dict(phase=phase, pid=pid, elapsedSeconds=elapsed, heapUsedBytes=12000000,
        nativeReservedBytes=184320000, nativeCommittedBytes=100663296 + native,
        javaHeapCommittedBytes=100663296, nativeNonHeapCommittedBytes=native,
        nativeThreadCount=25, rssBytes=rss, privateBytes=None)

class ResourceSoakTest(unittest.TestCase):
    def test_failure_report_aligns_owned_sampler_gaps_with_jvm_wall_time(self):
        probe = HostSchedulingProbe()
        probe.tick(0, 1000)
        probe.tick(400_000_000, 1400)
        output = io.StringIO()
        emit_bounded_failure_evidence(
            "RESOURCE_UNEXPECTED_HTTP_FAILURE method=GET mode=normal wallClockMs=1500\n",
            "http-disabled", output, host_probe=probe)
        value = output.getvalue()
        self.assertIn("RESOURCE_HOST_SCHEDULE kind=summary", value)
        self.assertIn("failureWallMs=1500", value)
        self.assertIn("nearPeakGapMs=300.000", value)
        self.assertNotIn("Authorization", value)

    def test_sampler_failure_preserves_only_bounded_owned_evidence(self):
        output = io.StringIO()
        text = ("Authorization=private-value\n"
            "RESOURCE_UNEXPECTED_HTTP_FAILURE method=GET factory=simple mode=normal elapsedMs=2200.0\n"
            "RESOURCE_TIMING kind=summary driverMaxLagMs=1200.0\n"
            "Maven failure details contain secret=private-value")
        emit_bounded_failure_evidence(text, "http-enabled", output)
        value = output.getvalue()
        self.assertIn("RESOURCE_UNEXPECTED_HTTP_FAILURE", value)
        self.assertIn("RESOURCE_TIMING kind=summary", value)
        self.assertNotIn("Authorization", value)
        self.assertNotIn("secret=", value)

    def test_safepoint_logging_is_opt_in_and_uses_only_the_owned_maven_jvm_file(self):
        self.assertEqual(safepoint_jvm_option("http-enabled", False, "http-enabled-20261007Z"), "")
        value = safepoint_jvm_option("http-disabled", True, "http-disabled-20261007Z")
        self.assertIn("-Xlog:safepoint=info:file=../target/resource-soak/http-disabled-20261007Z/safepoints.log", value)
        self.assertIn("uptimemillis,level,tags:filecount=0", value)
        self.assertNotIn("stdout", value)
        for component, name in (("agent", "agent-20261007Z"), ("http-enabled", "../other"),
                                ("http-enabled", "contains spaces")):
            with self.subTest(component=component, name=name), self.assertRaises(ValueError):
                safepoint_jvm_option(component, True, name)

    def test_failed_http_report_includes_bounded_numeric_safepoints_not_raw_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "safepoints.log"
            path.write_text('[14600ms][info][safepoint] Safepoint "PRIVATE_MARKER", Time since last: 100 ns, '
                'Reaching safepoint: 100000 ns, Cleanup: 10000 ns, At safepoint: 1599890000 ns, Total: 1600000000 ns\n',
                encoding="utf-8")
            workload = ('RESOURCE_UNEXPECTED_HTTP_FAILURE method=PUT mode=normal jvmUptimeMs=15000\n'
                'Maven assertion failed')
            output = io.StringIO()
            emit_failure_log(workload, "http-enabled", output, safepoint_path=path)
            rendered = output.getvalue()
            self.assertIn("RESOURCE_SAFEPOINT kind=summary", rendered)
            self.assertIn("nearPeakTotalMs=1600.000", rendered)
            self.assertNotIn("PRIVATE_MARKER", rendered)
            self.assertIn("Maven assertion failed", rendered)
            missing_output = io.StringIO()
            emit_failure_log(workload, "http-enabled", missing_output,
                safepoint_path=path.with_name("missing.log"))
            self.assertIn("RESOURCE_SAFEPOINT kind=unavailable reason=missing_file", missing_output.getvalue())
            self.assertIn("Maven assertion failed", missing_output.getvalue())

    def test_native_trim_stays_inside_owned_detailed_linux_http_probe(self):
        self.assertEqual(native_trim_arguments("http-enabled", False, False, "windows"), [])
        self.assertEqual(native_trim_arguments("http-disabled", True, True, "linux"),
            ["-Dtriage.resource.close-hold-seconds=75"])
        for component, details, platform in (("agent", True, "linux"), ("http-enabled", False, "linux"),
                                             ("http-enabled", True, "windows"), ("http-enabled", True, "darwin")):
            with self.subTest(component=component, details=details, platform=platform), self.assertRaises(ValueError):
                native_trim_arguments(component, True, details, platform)

    def test_timing_is_opt_in_for_the_owned_http_workload_only(self):
        self.assertEqual(http_timing_arguments("http-enabled", False), [])
        self.assertEqual(http_timing_arguments("http-disabled", True), ["-Dtriage.resource.timing=true"])
        with self.assertRaises(ValueError): http_timing_arguments("agent", True)

    def test_keeps_bounded_numeric_phase_timing_without_application_payload(self):
        text = "RESOURCE_TIMING kind=active request=3 stage=DELAY_HEADERS elapsedMs=2100.0 cpuNanos=-1\n" + \
            "requestPayload=never-export\nRESOURCE_FRAME Thread.sleep"
        value = failure_diagnostics(text)
        self.assertIn("RESOURCE_TIMING kind=active", value)
        self.assertNotIn("requestPayload", value)
        self.assertLessEqual(len(failure_diagnostics(text * 1000)), 32768)
    def test_windows_failure_output_does_not_mask_the_original_error_with_encoding_failure(self):
        raw = io.BytesIO()
        output = io.TextIOWrapper(raw, encoding="cp1252", errors="strict")
        emit_failure_log("RESOURCE_THREAD name=HTTP-Dispatcher state=WAITING\nMaven assertion failed: \ufffd", "http-disabled", output)
        value = raw.getvalue().decode("cp1252")
        self.assertIn("HTTP-Dispatcher", value)
        self.assertIn("Maven assertion failed", value)
        self.assertIn("\\ufffd", value)

    def test_keeps_owned_thread_evidence_when_maven_tail_would_truncate_it(self):
        evidence = "RESOURCE_THREAD name=HTTP-Dispatcher state=BLOCKED\nRESOURCE_FRAME sun.net.httpserver.ServerImpl.run\n"
        text = evidence + "unrelated request Authorization=do-not-export\n"*1000 + "Maven failed"
        value = failure_diagnostics(text)
        self.assertIn("HTTP-Dispatcher", value)
        self.assertIn("ServerImpl.run", value)
        self.assertNotIn("Authorization", value)
        self.assertLessEqual(len(failure_diagnostics(evidence*10000)), 32768)

    def test_attests_starting_source_and_rejects_head_changed_during_workload(self):
        self.assertEqual(source_provenance(("a"*40, False), ("a"*40, False)),
            dict(sourceCommit="a"*40, sourceTreeDirty=False))
        self.assertTrue(source_provenance(("a"*40, True), ("a"*40, False))["sourceTreeDirty"])
        with self.assertRaises(ValueError): source_provenance(("a"*40, False), ("b"*40, False))

    def test_parses_native_commit_without_confusing_heap_or_reserved_address_space(self):
        value = parse_native_memory(NMT, 1234)
        self.assertEqual(value["nativeCommittedBytes"], 143360000)
        self.assertEqual(value["javaHeapCommittedBytes"], 100663296)
        self.assertEqual(value["nativeNonHeapCommittedBytes"], 42696704)
        self.assertEqual(value["nativeThreadCount"], 25)

    def test_reads_plural_thread_label_from_ci_jdk_summary(self):
        text = "1234:\nTotal: reserved=1762235KB, committed=225807KB\n- Java Heap (reserved=262144KB, committed=98304KB)\n- Thread (reserved=28780KB, committed=1472KB)\n (threads #28)"
        value = parse_native_memory(text, 1234)
        self.assertEqual(value["nativeThreadCount"], 28)
        self.assertEqual(value["nativeCommittedBytes"], 231226368)

    def test_rejects_disabled_nmt_wrong_pid_and_inconsistent_totals(self):
        for text, pid in [("1234:\nNative memory tracking is not enabled", 1234),
            (NMT, 2222), (NMT.replace("committed=140000KB", "committed=90000KB"), 1234)]:
            with self.subTest(pid=pid), self.assertRaises(ValueError):
                parse_native_memory(text, pid)

    def test_requires_completed_workload_counters_and_cleanup(self):
        line = "RESOURCE_RESULT agent seconds=3600 cycles=12000 cancelled=60000 fresh=12000 baselineHeap=12000000 peakHeap=14000000 finalHeap=13000000 registry=0 queues=0 connections=0"
        result = parse_workload_result(line, "agent", 3600)
        self.assertEqual(result["cancelled"], 60000)
        for bad in [line.replace("seconds=3600", "seconds=300"), line.replace("registry=0", "registry=1"),
            line.replace("cancelled=60000", "cancelled=5"), "missing completion receipt"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                parse_workload_result(bad, "agent", 3600)

    def test_reads_heap_used_in_kib_and_mib(self):
        self.assertEqual(parse_heap("1234:\ngarbage-first heap total 98304K, used 12000K", 1234), 12288000)
        self.assertEqual(parse_heap("1234:\ngarbage-first heap total 96M, used 12M", 1234), 12582912)
        with self.assertRaises(ValueError):
            parse_heap("1234:\nUnexpected collector output", 1234)

    def test_warmup_peak_does_not_hide_or_invent_postwarmup_growth(self):
        samples = [sample(0, native=180000000, rss=400000000), sample(120),
            sample(150), sample(180, native=44000000), sample(200, "closed")]
        result = summarize(samples, 180, 120, 30)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["runningSamples"], 4)
        self.assertEqual(result["postWarmupPeakNativeGrowthBytes"], 1303296)

    def test_rejects_memory_growth_in_the_closed_phase(self):
        for closed in (sample(200, "closed", native=120000000), sample(200, "closed", rss=400000000)):
            closed["nativeReservedBytes"] = max(closed["nativeReservedBytes"], closed["nativeCommittedBytes"])
            with self.subTest(closed=closed), self.assertRaises(ValueError):
                summarize([sample(0), sample(120), sample(150), sample(180), closed], 180, 120, 30)

    def test_rejects_gc_heap_peak_above_the_workload_gate(self):
        line = "RESOURCE_RESULT agent seconds=60 cycles=20 cancelled=100 fresh=20 baselineHeap=12000000 peakHeap=80000000 finalHeap=12000000 registry=0 queues=0 connections=0"
        with self.assertRaises(ValueError):
            parse_workload_result(line, "agent", 60)

    def test_rejects_a_missing_thirty_second_sample_at_each_steady_boundary(self):
        cases = [
            ([sample(0), sample(120), sample(150), sample(195), sample(220, "closed")], 210),
            ([sample(0), sample(165), sample(195), sample(225), sample(245, "closed")], 240),
            ([sample(0), sample(120), sample(150), sample(180), sample(225, "closed")], 220),
        ]
        for rows, seconds in cases:
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                summarize(rows, seconds, 120, 30)

    def test_rejects_sampler_that_stops_long_before_the_workload_closes(self):
        samples = [sample(0), sample(120), sample(150), sample(180), sample(1000, "closed")]
        with self.assertRaises(ValueError):
            summarize(samples, 1000, 120, 30)

    def test_rejects_growth_and_missing_or_short_running_coverage(self):
        valid = [sample(0), sample(120), sample(150), sample(180), sample(200, "closed")]
        for samples in [valid[:-1], valid[:2] + [sample(60, "closed")],
            valid[:3] + [sample(180, native=120000000), valid[-1]],
            valid[:3] + [sample(180, rss=320000000), valid[-1]],
            valid[:3] + [sample(180, pid=9999), valid[-1]],
            [sample(0), sample(120), sample(150), sample(300), sample(320, "closed")]]:
            with self.subTest(samples=samples), self.assertRaises(ValueError):
                summarize(samples, 180, 120, 30)

if __name__ == "__main__":
    unittest.main()
