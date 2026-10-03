"""Validate the real HTTP workload contract separately from component loops."""
import csv
import json
from pathlib import Path
import tempfile
import unittest
from resource_soak import parse_workload_result, parse_native_memory
from resource_report import verify_matrix, verify_dataset
import test_resource_report as report_fixtures


def receipt(component="http-enabled", seconds=60):
    cycles = seconds
    enabled = component == "http-enabled"
    return dict(seconds=seconds, cycles=cycles, rps=8, concurrency=4, requests=cycles*8,
        successes=cycles*3, failures=cycles*5, typedTimeouts=cycles*4, untypedFailures=cycles,
        observedRequests=cycles*8 if enabled else 0, observedTimeouts=cycles*4 if enabled else 0,
        expectedWindowRequests=seconds*8 if seconds < 60 else 472, expectedWindowTimeouts=seconds*4 if seconds < 60 else 236,
        windowRequests=(seconds*8 if seconds < 60 else 472) if enabled else 0,
        windowTimeouts=(seconds*4 if seconds < 60 else 236) if enabled else 0,
        responsesOpened=cycles*7, responsesClosed=cycles*7, downstreamRequests=cycles*8,
        servletContexts=0, workerContexts=0, downstreamActive=0, queues=0, payloadErrors=0,
        executorsStopped=True, servletStopped=True, maxLagMillis=100,
        baselineHeap=12000000, peakHeap=14000000, finalHeap=13000000)


def line(component, fields):
    return "RESOURCE_RESULT " + component + " " + " ".join(
        k + "=" + (str(v).lower() if isinstance(v, bool) else str(v)) for k, v in fields.items())


class HttpResourcesTest(unittest.TestCase):
    def test_both_modes_validate_same_load_and_distinct_observation_counts(self):
        for component in ("http-disabled", "http-enabled"):
            counters = receipt(component)
            self.assertEqual(parse_workload_result(line(component, counters), component, 60), counters)

    def test_rejects_wrong_counts_open_responses_contexts_cleanup_and_rate(self):
        for key, bad in (("requests", 479), ("successes", 179), ("failures", 299),
                ("typedTimeouts", 239), ("untypedFailures", 0), ("observedRequests", 479),
                ("observedTimeouts", 239), ("responsesClosed", 0), ("responsesOpened", 0),
                ("downstreamRequests", 0), ("servletContexts", 1), ("workerContexts", 1),
                ("downstreamActive", 1), ("queues", 1), ("payloadErrors", 1),
                ("executorsStopped", False), ("servletStopped", False),
                ("rps", 9), ("concurrency", 8), ("maxLagMillis", 2001),
                ("windowRequests", 473), ("windowTimeouts", 237), ("expectedWindowRequests", 485), ("seconds", 59)):
            with self.subTest(key=key):
                counters = receipt(); counters[key] = bad
                with self.assertRaises(ValueError):
                    parse_workload_result(line("http-enabled", counters), "http-enabled", 60)
        counters = receipt("http-disabled"); counters["observedRequests"] = 480
        with self.assertRaises(ValueError):
            parse_workload_result(line("http-disabled", counters), "http-disabled", 60)

    def test_receipt_rejects_duplicate_fields_junk_and_missing_values(self):
        valid = line("http-enabled", receipt())
        for bad in (valid + " requests=480", valid + " secrets=hidden", valid.replace("rps=8", "rps=-8"),
                    valid.replace("rps=8 ", ""), valid + "\n" + valid):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                parse_workload_result(bad, "http-enabled", 60)

    def test_nmt_breakdown_preserves_total_including_unclassified_categories(self):
        text = """1234:
Total: reserved=180000KB, committed=140000KB
- Java Heap (reserved=131072KB, committed=98304KB)
- Class (reserved=5000KB, committed=2000KB)
- Thread (reserved=5000KB, committed=1000KB)
    (threads #25)
- Code (reserved=8000KB, committed=3000KB)
- GC (reserved=10000KB, committed=4000KB)
- Other (reserved=1500KB, committed=1500KB)
"""
        value = parse_native_memory(text, 1234)
        fields = ("nativeClassCommittedBytes", "nativeThreadCommittedBytes", "nativeCodeCommittedBytes",
            "nativeGCCommittedBytes", "nativeOtherCommittedBytes", "nativeUncategorizedCommittedBytes")
        self.assertEqual(sum(value[field] for field in fields), value["nativeNonHeapCommittedBytes"])
        self.assertEqual(value["nativeGCCommittedBytes"], 4096000)

    def test_http_matrix_requires_both_modes_on_each_platform_and_rejects_component_mix(self):
        with tempfile.TemporaryDirectory() as directory:
            summaries = []
            for component in ("http-disabled", "http-enabled"):
                for system in ("linux", "windows"):
                    root = Path(directory)/(component+"-"+system); root.mkdir()
                    meta = report_fixtures.ResourceReportTest().fixture(root)
                    meta.update(component=component, operatingSystem=system, workload=receipt(component))
                    with (root/"memory.csv").open(newline="") as source: rows = list(csv.DictReader(source))
                    for row in rows:
                        for key in ("nativeClassCommittedBytes", "nativeThreadCommittedBytes", "nativeCodeCommittedBytes", "nativeGCCommittedBytes", "nativeOtherCommittedBytes"):
                            row[key] = "0"
                        row["nativeUncategorizedCommittedBytes"] = row["nativeNonHeapCommittedBytes"]
                    if system == "windows":
                        for row in rows: row["privateBytes"] = "150000000"
                        meta["baseline"]["privateBytes"] = meta["closedSample"]["privateBytes"] = 150000000
                    with (root/"memory.csv").open("w", newline="") as output:
                        writer=csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
                    for key in ("nativeClassCommittedBytes", "nativeThreadCommittedBytes", "nativeCodeCommittedBytes", "nativeGCCommittedBytes", "nativeOtherCommittedBytes", "nativeUncategorizedCommittedBytes"):
                        meta["baseline"][key] = int(rows[2][key]); meta["closedSample"][key] = int(rows[-1][key])
                    summary=root/"summary.json"; summary.write_text(json.dumps(meta)); summaries.append(summary)
            datasets = verify_matrix(summaries, "a"*40, 60, workload="http")
            self.assertEqual(len(datasets), 4)
            for invalid in (summaries[:-1], summaries+[summaries[0]], summaries[:2]+summaries[:2]):
                with self.assertRaises(ValueError): verify_matrix(invalid, "a"*40, 60, workload="http")
            with self.assertRaises(ValueError): verify_matrix(summaries, "a"*40, 60)
            first = json.loads(summaries[0].read_text())
            first["workload"].update(connectionPolicy=2, healthyConnectionsReused=True, faultConnectionsClosed=True)
            summaries[0].write_text(json.dumps(first))
            with self.assertRaisesRegex(ValueError, "mixes connection policies"):
                verify_matrix(summaries, "a"*40, 60, workload="http")

    def test_http_receipt_requires_native_breakdown_and_rejects_inconsistent_categories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); meta = report_fixtures.ResourceReportTest().fixture(root)
            meta.update(component="http-enabled", workload=receipt())
            (root/"summary.json").write_text(json.dumps(meta))
            with self.assertRaises(ValueError): verify_dataset(root/"summary.json", "a"*40, 60)

    def test_final_window_cannot_hide_missing_traffic_or_timeouts(self):
        for seconds in (10, 60, 600, 3600):
            for field in ("Requests", "Timeouts"):
                with self.subTest(seconds=seconds, field=field):
                    counters = receipt(seconds=seconds)
                    counters["expectedWindow"+field] = counters["window"+field] = 1
                    with self.assertRaises(ValueError):
                        parse_workload_result(line("http-enabled", counters), "http-enabled", seconds)

    def test_owned_http_nmt_requires_actual_class_code_thread_and_gc_measurements(self):
        text = "1234:\nTotal: reserved=180000KB, committed=140000KB\n- Java Heap (reserved=131072KB, committed=98304KB)\n- Thread (reserved=5000KB, committed=1000KB)\n (thread #25)"
        with self.assertRaises(ValueError): parse_native_memory(text, 1234, require_breakdown=True)

    def test_final_minute_near_one_minute_duration_can_naturally_expire_first_requests(self):
        counters = receipt(seconds=59)
        counters["expectedWindowRequests"] = counters["windowRequests"] = 469
        self.assertEqual(parse_workload_result(line("http-enabled", counters), "http-enabled", 59), counters)

    def test_fault_closed_profile_requires_actual_healthy_reuse_and_all_flags(self):
        counters = dict(receipt(), connectionPolicy=2, healthyConnectionsReused=True, faultConnectionsClosed=True)
        self.assertEqual(parse_workload_result(line("http-enabled", counters), "http-enabled", 60), counters)
        for key, bad in (("connectionPolicy", 3), ("healthyConnectionsReused", False), ("faultConnectionsClosed", False)):
            with self.subTest(key=key):
                changed = dict(counters); changed[key] = bad
                with self.assertRaises(ValueError): parse_workload_result(line("http-enabled", changed), "http-enabled", 60)
        changed = dict(counters); del changed["faultConnectionsClosed"]
        with self.assertRaises(ValueError): parse_workload_result(line("http-enabled", changed), "http-enabled", 60)


if __name__ == "__main__":
    unittest.main()
