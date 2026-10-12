"""Reject non-finite or inconsistent values in cost-artifact replay."""
import csv
import hashlib
import json
from pathlib import Path
import unittest
import uuid

from petclinic_multi_cost import REQUEST_COLUMNS
from petclinic_multi_cost_report import check_close, verify
from prepare_petclinic_rest import REVISION


class CostReportTest(unittest.TestCase):
    SOURCE = "a" * 40

    def fixture(self):
        folder = Path("target/petclinic-cost-report-test") / uuid.uuid4().hex
        folder.mkdir(parents=True)
        requests = [dict(index=i, scheduledElapsedSeconds=round(i * .5, 3),
            startedElapsedSeconds=round(i * .5, 3), startLagMs=0, durationMs=100) for i in range(120)]
        samples = [dict(pid=123, elapsedSeconds=seconds, rssBytes=(400 + index) * 1048576,
            privateBytes=(300 + index) * 1048576) for index, seconds in enumerate((0, 15, 30, 45, 60, 62))]
        samples[-1]["rssBytes"] = samples[-2]["rssBytes"]
        samples[-1]["privateBytes"] = samples[-2]["privateBytes"]
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.write_csv(folder / "cost.csv", ("pid", "elapsedSeconds", "rssBytes", "privateBytes"), samples)
        summary = dict(artifactVersion=2, durationSeconds=60, completedRequests=120, observedRps=2.0,
            warmupSeconds=12.0, requestP50Ms=100, requestP95Ms=100, requestMaxMs=100,
            sampleCount=6, maxStartLagMs=0, maxInterStartGapMs=500,
            baselineRssBytes=samples[1]["rssBytes"], peakRssBytes=samples[-2]["rssBytes"],
            rssPeakGrowthBytes=3 * 1048576, finalRssBytes=samples[-1]["rssBytes"],
            baselinePrivateBytes=samples[1]["privateBytes"], privatePeakGrowthBytes=3 * 1048576,
            sourceCommit=self.SOURCE, upstreamRevision=REVISION, observedRequests=122,
            inventoryTimeouts=122, payment2xx=61, payment5xx=61, agentValidated=True)
        self.rehash(folder, summary)
        return folder, summary, requests, samples

    def write_csv(self, path, fields, values):
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            writer.writerows(values)

    def rehash(self, folder, summary):
        summary["requestsCsvSha256"] = hashlib.sha256((folder / "requests.csv").read_bytes()).hexdigest()
        summary["memoryCsvSha256"] = hashlib.sha256((folder / "cost.csv").read_bytes()).hexdigest()
        (folder / "cost-summary.json").write_text(json.dumps(summary), encoding="utf-8")

    def test_version_two_receipt_replays_its_complete_numeric_files(self):
        folder, _, _, _ = self.fixture()
        self.assertEqual(verify(folder, self.SOURCE, 60)["requests"], 120)

    def test_intermediate_memory_tampering_is_rejected(self):
        folder, _, _, samples = self.fixture()
        samples[2]["rssBytes"] = 1
        self.write_csv(folder / "cost.csv", ("pid", "elapsedSeconds", "rssBytes", "privateBytes"), samples)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verify(folder, self.SOURCE, 60)

    def test_early_start_and_nonfinite_duration_are_rejected_even_after_rehashing(self):
        folder, summary, requests, _ = self.fixture()
        requests[1]["startedElapsedSeconds"] = .4
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "prematurely"):
            verify(folder, self.SOURCE, 60)
        requests[1]["startedElapsedSeconds"] = .5
        requests[0]["durationMs"] = "nan"
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "Non-finite"):
            verify(folder, self.SOURCE, 60)

    def test_invalid_warmup_is_rejected(self):
        folder, summary, _, _ = self.fixture()
        summary["warmupSeconds"] = -1
        (folder / "cost-summary.json").write_text(json.dumps(summary), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Numeric cost summary"):
            verify(folder, self.SOURCE, 60)

    def test_comparison_rejects_nonfinite_values_on_either_side(self):
        for actual, expected in ((float("nan"), 0), (0, float("nan")),
                                 (float("inf"), 0), (0, float("-inf"))):
            with self.subTest(actual=actual, expected=expected), self.assertRaises(ValueError):
                check_close(actual, expected)

    def test_comparison_keeps_numeric_tolerance_bounded(self):
        check_close(100.005, 100)
        with self.assertRaises(ValueError):
            check_close(100.02, 100)


if __name__ == "__main__":
    unittest.main()
