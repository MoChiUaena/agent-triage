"""Guard diagnostic parsing, incomplete coverage and growth failures independently of workload code."""
import unittest
from resource_soak import parse_native_memory, parse_heap, summarize, parse_workload_result, failure_diagnostics, source_provenance

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
