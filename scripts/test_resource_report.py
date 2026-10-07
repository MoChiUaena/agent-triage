import csv
import json
from pathlib import Path
import tempfile
import unittest
import shutil
from resource_report import verify_dataset, verify_matrix, render_table
from resource_memory_details import NMT_CATEGORIES, make_details_sample, append_details_sample, details_receipt
from resource_native_trim import make_trim_row, trim_receipt, write_trim_file

class ResourceReportTest(unittest.TestCase):
    def test_native_trim_receipt_replays_only_with_bound_linux_http_samples(self):
        archived = Path(__file__).resolve().parents[1] / 'docs/validation/samples/2026-10-07-http-timing/60s/http-enabled-linux'
        source_commit = '8f2df4b2a66b3cab90ab68dc85694f05953e4418'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ('summary.json', 'memory.csv', 'memory-details.csv'):
                shutil.copyfile(archived / name, root / name)
            meta, base = verify_dataset(root / 'summary.json', source_commit, 60)
            closed, detail = base[-1], meta['_memoryDetailsRows'][-1]
            before = make_trim_row('before', closed, detail)
            after_base = dict(closed, elapsedSeconds=closed['elapsedSeconds'] + 2, rssBytes=closed['rssBytes'] - 4096)
            after_detail = dict(detail, rollupRssBytes=detail['rollupRssBytes'] - 4096,
                rollupAnonymousBytes=detail['rollupAnonymousBytes'] - 4096)
            after = make_trim_row('after', after_base, after_detail)
            path = root / 'native-heap-trim.csv'
            write_trim_file(path, (before, after))
            meta['nativeHeapTrim'] = trim_receipt(path)
            meta['workflowRunId'] = 123
            meta.pop('_memoryDetailsRows', None)
            (root / 'summary.json').write_text(json.dumps(meta), encoding='utf-8')
            replayed, rows = verify_dataset(root / 'summary.json', source_commit, 60)
            self.assertEqual(replayed['_nativeTrimRows'][1]['rssBytes'], after_base['rssBytes'])
            path.write_bytes(path.read_bytes() + b'0')
            with self.assertRaises(ValueError): verify_dataset(root / 'summary.json', source_commit, 60)
            path.write_bytes(path.read_bytes()[:-1])
            meta['workflowRunId'] = '123'
            (root / 'summary.json').write_text(json.dumps(meta), encoding='utf-8')
            with self.assertRaises(ValueError): verify_dataset(root / 'summary.json', source_commit, 60)

    def test_rejects_non_http_or_non_boolean_timing_profiles(self):
        for value in (True, 1, "true"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); meta = self.fixture(root); meta["httpTiming"] = value
                (root / "summary.json").write_text(json.dumps(meta))
                with self.assertRaises(ValueError): verify_dataset(root / "summary.json", "a" * 40, 60)
    def detailed_fixture(self, root):
        meta = self.fixture(root)
        core = dict(nativeClassCommittedBytes=6000000, nativeThreadCommittedBytes=1000000,
            nativeCodeCommittedBytes=10000000, nativeGCCommittedBytes=15000000,
            nativeOtherCommittedBytes=0, nativeUncategorizedCommittedBytes=11000000)
        with (root / "memory.csv").open(newline="") as source: rows = list(csv.DictReader(source))
        for row in rows: row.update(core)
        with (root / "memory.csv").open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
        meta["baseline"].update(core); meta["closedSample"].update(core)
        nmt = dict.fromkeys(NMT_CATEGORIES)
        nmt.update(nativeMetaspaceCommittedBytes=8000000, nativeSymbolCommittedBytes=1000000, nativeArenaChunkCommittedBytes=2000000)
        rollup = dict(rollupRssBytes=170000000, rollupPssBytes=120000000, rollupPrivateCleanBytes=20000000,
            rollupPrivateDirtyBytes=80000000, rollupSharedCleanBytes=65000000, rollupSharedDirtyBytes=5000000,
            rollupAnonymousBytes=100000000, rollupSwapBytes=0, rollupPssAnonBytes=90000000,
            rollupPssFileBytes=30000000, rollupPssShmemBytes=0)
        path = root / "memory-details.csv"
        for raw in rows:
            base = dict(phase=raw["phase"], pid=int(raw["pid"]), elapsedSeconds=float(raw["elapsedSeconds"]), **core)
            append_details_sample(path, make_details_sample(base, nmt, rollup))
        meta["memoryDetails"] = details_receipt(path, len(rows))
        (root / "summary.json").write_text(json.dumps(meta))
        return meta

    def test_replays_bound_memory_details_when_the_receipt_declares_them(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.detailed_fixture(root)
            meta, rows = verify_dataset(root / "summary.json", "a" * 40, 60)
            self.assertEqual(len(meta.get("_memoryDetailsRows", [])), 12)
            self.assertEqual(meta["_memoryDetailsRows"][0]["nativeArenaChunkCommittedBytes"], 2000000)

    def test_rejects_missing_or_altered_details_instead_of_ignoring_their_manifest(self):
        for kind in ("missing", "altered", "resigned-wrong-time", "unknown-version"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); meta = self.detailed_fixture(root)
                path = root / "memory-details.csv"
                if kind == "missing": path.unlink()
                elif kind == "altered": path.write_bytes(path.read_bytes() + b"extra\r\n")
                elif kind == "resigned-wrong-time":
                    path.write_bytes(path.read_bytes().replace(b"running,1234,0.0,", b"running,1234,1.0,"))
                    meta["memoryDetails"] = details_receipt(path, 12)
                else: meta["memoryDetails"]["version"] = 99
                (root / "summary.json").write_text(json.dumps(meta))
                with self.assertRaises(ValueError): verify_dataset(root / "summary.json", "a" * 40, 60)

    def test_matrix_rejects_mixing_detailed_and_legacy_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); summaries = self.matrix_fixture(root)
            self.detailed_fixture(summaries[0].parent)
            with self.assertRaises(ValueError): verify_matrix(summaries, "a" * 40, 60)

    def test_render_uses_verified_details_and_keeps_unreported_fields_distinct_from_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.detailed_fixture(root)
            dataset = verify_dataset(root / "summary.json", "a" * 40, 60)
            table = render_table([dataset])
            self.assertIn("Arena Chunk growth", table)
            self.assertIn("| agent | linux | 0.00 | 0.00 | not reported |", table)
            self.assertIn("File PSS growth", table)

    def fixture(self, root):
        rows = [dict(phase="running", pid=1234, elapsedSeconds=elapsed, nativeReservedBytes=180000000,
            nativeCommittedBytes=143000000, javaHeapCommittedBytes=100000000, nativeNonHeapCommittedBytes=43000000,
            nativeThreadCount=25, heapUsedBytes=12000000, rssBytes=170000000, privateBytes=None)
            for elapsed in range(0, 61, 6)]
        closed = dict(rows[-1], phase="closed", elapsedSeconds=65); rows.append(closed)
        meta = dict(status="passed", component="agent", operatingSystem="linux", sourceCommit="a" * 40, sourceTreeDirty=False,
            requestedSeconds=60, warmupSeconds=12, intervalSeconds=6, runningSamples=11, baseline=rows[2], closedSample=closed,
            postWarmupPeakNativeGrowthBytes=0, postWarmupPeakResidentGrowthBytes=0, peakNativeThreadCount=25, peakHeapUsedBytes=12000000,
            nativeGrowthGateBytes=67108864, residentGrowthGateBytes=134217728, workload=dict(seconds=60, cycles=20, cancelled=100, fresh=20,
                baselineHeap=12000000, peakHeap=13000000, finalHeap=12000000, registry=0, queues=0, connections=0))
        with (root / "memory.csv").open("w", newline="", encoding="utf-8") as output:
            writer = csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
        (root / "summary.json").write_text(json.dumps(meta))
        return meta

    def test_replays_actual_numeric_rows_and_source_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            meta, rows = verify_dataset(root / "summary.json", "a" * 40, 60)
            self.assertEqual(meta["runningSamples"], 11)
            self.assertEqual(rows[-1]["elapsedSeconds"], 65)

    def test_rejects_summary_tampering_or_wrong_source_duration_and_dirty_tree(self):
        for key, changed in [("sourceCommit", "b" * 40), ("requestedSeconds", 3600), ("sourceTreeDirty", True),
            ("postWarmupPeakNativeGrowthBytes", 1), ("intervalSeconds", 300)]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); meta = self.fixture(root); meta[key] = changed
                (root / "summary.json").write_text(json.dumps(meta))
                with self.assertRaises(ValueError):
                    verify_dataset(root / "summary.json", "a" * 40, 60)

    def test_rejects_csv_rows_that_confuse_native_heap_or_missing_os_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.fixture(root)
            text = (root / "memory.csv").read_text()
            (root / "memory.csv").write_text(text.replace("143000000", "90000000"))
            with self.assertRaises(ValueError):
                verify_dataset(root / "summary.json", "a" * 40, 60)


    def test_rejects_missing_platform_measurements_and_incomplete_workloads(self):
        for edit in ("missing-rss", "windows-private", "negative-counter", "borrowed-connection", "missing-counter"):
            with self.subTest(edit=edit), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); meta = self.fixture(root)
                if edit == "missing-rss":
                    (root / "memory.csv").write_text((root / "memory.csv").read_text().replace("170000000", ""))
                elif edit == "windows-private":
                    meta["operatingSystem"] = "windows"
                elif edit == "negative-counter":
                    meta["workload"]["peakHeap"] = -1
                elif edit == "borrowed-connection":
                    meta["workload"]["connections"] = 1
                else:
                    del meta["workload"]["cancelled"]
                (root / "summary.json").write_text(json.dumps(meta))
                with self.assertRaises(ValueError):
                    verify_dataset(root / "summary.json", "a" * 40, 60)


    def test_legacy_receipt_replays_original_summary_then_checks_closed_growth(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); meta = self.fixture(root)
            with (root / "memory.csv").open(newline="") as source:
                rows = list(csv.DictReader(source))
            rows[-1].update(nativeCommittedBytes="153000000", nativeNonHeapCommittedBytes="53000000")
            meta["closedSample"].update(nativeCommittedBytes=153000000, nativeNonHeapCommittedBytes=53000000)
            with (root / "memory.csv").open("w", newline="") as output:
                writer = csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
            (root / "summary.json").write_text(json.dumps(meta))
            verified, _ = verify_dataset(root / "summary.json", "a" * 40, 60)
            self.assertIs(verified["growthIncludesClosed"], True)
            self.assertEqual(verified["postWarmupPeakNativeGrowthBytes"], 10000000)
            meta["growthIncludesClosed"] = True
            meta["postWarmupPeakNativeGrowthBytes"] = 10000000
            (root / "summary.json").write_text(json.dumps(meta))
            self.assertEqual(verify_dataset(root / "summary.json", "a" * 40, 60)[0]["postWarmupPeakNativeGrowthBytes"], 10000000)
            meta["postWarmupPeakNativeGrowthBytes"] = 0
            (root / "summary.json").write_text(json.dumps(meta))
            with self.assertRaises(ValueError):
                verify_dataset(root / "summary.json", "a" * 40, 60)

    def matrix_fixture(self, root):
        summaries = []
        for component in ("agent", "starter"):
            for operating_system in ("linux", "windows"):
                directory = root / (component + "-" + operating_system); directory.mkdir()
                meta = self.fixture(directory)
                meta.update(component=component, operatingSystem=operating_system)
                if component == "starter":
                    meta["workload"] = dict(seconds=60, cycles=20, cancelled=20, requests=20, jdbc=40,
                        baselineHeap=12000000, peakHeap=13000000, finalHeap=12000000, connections=0,
                        workerContexts=0, samplerStopped=True)
                if operating_system == "windows":
                    with (directory / "memory.csv").open(newline="") as source:
                        rows = list(csv.DictReader(source))
                    for row in rows: row["privateBytes"] = "150000000"
                    with (directory / "memory.csv").open("w", newline="") as output:
                        writer = csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
                    meta["baseline"]["privateBytes"] = 150000000
                    meta["closedSample"]["privateBytes"] = 150000000
                summary = directory / "summary.json"; summary.write_text(json.dumps(meta)); summaries.append(summary)
        return summaries

    def test_matrix_requires_each_component_and_platform_once(self):
        with tempfile.TemporaryDirectory() as directory:
            summaries = self.matrix_fixture(Path(directory))
            datasets = verify_matrix(summaries, "a" * 40, 60)
            self.assertEqual(len(datasets), 4)
            self.assertEqual({(meta["component"], meta["operatingSystem"]) for meta, rows in datasets},
                {("agent", "linux"), ("agent", "windows"), ("starter", "linux"), ("starter", "windows")})
            for invalid in (summaries[:-1], summaries + [summaries[0]], []):
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    verify_matrix(invalid, "a" * 40, 60)

if __name__ == "__main__":
    unittest.main()
