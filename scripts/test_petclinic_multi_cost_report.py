"""Reject non-finite or inconsistent values in cost-artifact replay."""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest
import uuid

from petclinic_multi_cost import REQUEST_COLUMNS
from petclinic_multi_cost_report import check_close, verify
from prepare_petclinic_rest import REVISION


class CostReportTest(unittest.TestCase):
    SOURCE = "a" * 40

    def fixture(self, seconds=60):
        folder = Path("target/petclinic-cost-report-test") / uuid.uuid4().hex
        folder.mkdir(parents=True)
        requests = [dict(index=i, scheduledElapsedSeconds=round(i * .5, 3),
            startedElapsedSeconds=round(i * .5, 3), startLagMs=0, durationMs=100) for i in range(seconds * 2)]
        samples = [dict(pid=123, elapsedSeconds=seconds, rssBytes=(400 + index) * 1048576,
            privateBytes=(300 + index) * 1048576) for index, seconds in enumerate(
                [*range(0, seconds + 1, 15), seconds + 2])]
        samples[-1]["rssBytes"] = samples[-2]["rssBytes"]
        samples[-1]["privateBytes"] = samples[-2]["privateBytes"]
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.write_csv(folder / "cost.csv", ("pid", "elapsedSeconds", "rssBytes", "privateBytes"), samples)
        baseline = next(row for row in samples if row["elapsedSeconds"] >= seconds / 5)
        summary = dict(artifactVersion=2, durationSeconds=seconds, completedRequests=seconds * 2,
            observedRps=2.0, warmupSeconds=seconds / 5, requestP50Ms=100, requestP95Ms=100,
            requestMaxMs=100, sampleCount=len(samples), maxStartLagMs=0, maxInterStartGapMs=500,
            baselineRssBytes=baseline["rssBytes"], peakRssBytes=samples[-2]["rssBytes"],
            rssPeakGrowthBytes=samples[-2]["rssBytes"] - baseline["rssBytes"],
            finalRssBytes=samples[-1]["rssBytes"], baselinePrivateBytes=baseline["privateBytes"],
            privatePeakGrowthBytes=samples[-2]["privateBytes"] - baseline["privateBytes"],
            sourceCommit=self.SOURCE, upstreamRevision=REVISION, observedRequests=seconds * 2 + 2,
            inventoryTimeouts=seconds * 2 + 2, payment2xx=seconds + 1, payment5xx=seconds + 1,
            agentValidated=True)
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
        self.assertEqual(verify(folder, self.SOURCE, 60, trust_current_run=True)["requests"], 120)
        with self.assertRaisesRegex(ValueError, "trusted summary"):
            verify(folder, self.SOURCE, 60)

    def test_ten_minute_receipt_is_accepted_by_public_replay_command(self):
        folder, _, _, _ = self.fixture(600)
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("petclinic_multi_cost_report.py")),
            str(folder), "--source-commit", self.SOURCE, "--seconds", "600", "--trust-current-run"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["requests"], 1200)

    def test_hour_receipt_uses_lifetime_count_and_rolling_window(self):
        folder, summary, _, _ = self.fixture(3600)
        summary.update(artifactVersion=3, observedRequests=1796, inventoryTimeouts=1796,
            payment2xx=898, payment5xx=898, recordedRequests=7202,
            observationWindowMinutes=15, agentWindowRequests=1792)
        self.rehash(folder, summary)
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("petclinic_multi_cost_report.py")),
            str(folder), "--source-commit", self.SOURCE, "--seconds", "3600", "--trust-current-run"],
            capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["requests"], 7200)
        summary["recordedRequests"] = 7201
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "lifetime|recorded|count"):
            verify(folder, self.SOURCE, 3600, trust_current_run=True)

    def test_long_receipt_reports_late_vs_early_memory_without_changing_acceptance(self):
        folder, _, _, _ = self.fixture(600)
        result = verify(folder, self.SOURCE, 600, trust_current_run=True)
        self.assertEqual(result["rssLateMinusEarlyMiB"], 25.0)
        self.assertEqual(result["privateLateMinusEarlyMiB"], 25.0)

    def test_same_runner_control_requires_its_own_numeric_receipt(self):
        folder, summary, _, _ = self.fixture()
        control = {key: value for key, value in summary.items() if key not in
            {"observedRequests", "inventoryTimeouts", "payment2xx", "payment5xx", "agentValidated"}}
        control.update(starterEnabled=False, functionalRequests=2)
        (folder / "control-requests.csv").write_bytes((folder / "requests.csv").read_bytes())
        (folder / "control-cost.csv").write_bytes((folder / "cost.csv").read_bytes())
        (folder / "control-summary.json").write_text(json.dumps(control), encoding="utf-8")
        result = verify(folder, self.SOURCE, 60, trust_current_run=True, control=True)
        self.assertEqual(result["requests"], 120)
        control["functionalRequests"] = 1
        (folder / "control-summary.json").write_text(json.dumps(control), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "control"):
            verify(folder, self.SOURCE, 60, trust_current_run=True, control=True)

    def test_intermediate_memory_tampering_is_rejected(self):
        folder, _, _, samples = self.fixture()
        samples[2]["rssBytes"] = 1
        self.write_csv(folder / "cost.csv", ("pid", "elapsedSeconds", "rssBytes", "privateBytes"), samples)
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verify(folder, self.SOURCE, 60, trust_current_run=True)

    def test_external_summary_digest_rejects_coordinated_file_rewrite(self):
        folder, summary, _, samples = self.fixture()
        original_sha = hashlib.sha256((folder / "cost-summary.json").read_bytes()).hexdigest()
        samples[2]["rssBytes"] = 1
        self.write_csv(folder / "cost.csv", ("pid", "elapsedSeconds", "rssBytes", "privateBytes"), samples)
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "trusted SHA-256"):
            verify(folder, self.SOURCE, 60, expected_summary_sha=original_sha)

    def test_early_start_and_nonfinite_duration_are_rejected_even_after_rehashing(self):
        folder, summary, requests, _ = self.fixture()
        requests[1]["startedElapsedSeconds"] = .4
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "prematurely"):
            verify(folder, self.SOURCE, 60, trust_current_run=True)
        requests[1]["startedElapsedSeconds"] = .5
        requests[0]["durationMs"] = "nan"
        self.write_csv(folder / "requests.csv", REQUEST_COLUMNS, requests)
        self.rehash(folder, summary)
        with self.assertRaisesRegex(ValueError, "Non-finite"):
            verify(folder, self.SOURCE, 60, trust_current_run=True)

    def test_invalid_warmup_is_rejected(self):
        folder, summary, _, _ = self.fixture()
        summary["warmupSeconds"] = -1
        (folder / "cost-summary.json").write_text(json.dumps(summary), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Numeric cost summary"):
            verify(folder, self.SOURCE, 60, trust_current_run=True)

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
