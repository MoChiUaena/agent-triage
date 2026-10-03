import csv
import hashlib
from pathlib import Path
import tempfile
import unittest
from resource_memory_details import (parse_nmt_details, parse_linux_rollup, make_details_sample,
    append_details_sample, details_receipt, verify_details_file, detail_growth)

NMT = """1234:
Native Memory Tracking:
Total: reserved=180000KB, committed=140000KB
- Metaspace (reserved=65536KB, committed=42000KB)
- Arena Chunk (reserved=512KB, committed=512KB)
- Symbol (reserved=15000KB, committed=15000KB)
- PrivateApplicationPayload (reserved=1KB, committed=1KB)
"""
ROLLUP = """12340000-56780000 ---p 00000000 00:00 0 [rollup]
Rss:                4096 kB
Pss:                3072 kB
Pss_Anon:           2048 kB
Pss_File:           1024 kB
Pss_Shmem:             0 kB
Shared_Clean:       1024 kB
Shared_Dirty:        256 kB
Private_Clean:      1280 kB
Private_Dirty:      1536 kB
Anonymous:          2304 kB
Swap:                 16 kB
"""

class ResourceMemoryDetailsTest(unittest.TestCase):
    def test_parses_reported_categories_and_leaves_unreported_categories_unknown(self):
        value = parse_nmt_details(NMT, 1234)
        self.assertEqual(value.get("nativeMetaspaceCommittedBytes"), 43008000)
        self.assertEqual(value.get("nativeArenaChunkCommittedBytes"), 524288)
        self.assertEqual(value.get("nativeSymbolCommittedBytes"), 15360000)
        self.assertIsNone(value.get("nativeCompilerCommittedBytes"))
        self.assertNotIn("PrivateApplicationPayload", str(value))

    def test_rejects_duplicate_malformed_or_inconsistent_reported_nmt_categories(self):
        for text in (NMT + "- Arena Chunk (reserved=512KB, committed=512KB)\n",
                     NMT.replace("committed=512KB", "committed=badKB"),
                     NMT.replace("committed=512KB", "committed=513KB")):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_nmt_details(text, 1234)

    def test_rejects_details_from_another_jvm(self):
        with self.assertRaises(ValueError): parse_nmt_details(NMT, 9999)

    def test_reads_linux_resident_components_and_proportional_shares_without_addresses(self):
        value = parse_linux_rollup(ROLLUP)
        self.assertEqual(value.get("rollupRssBytes"), 4194304)
        self.assertEqual(value.get("rollupPssBytes"), 3145728)
        self.assertEqual(value.get("rollupAnonymousBytes"), 2359296)
        self.assertEqual(value.get("rollupPssFileBytes"), 1048576)
        self.assertEqual(value.get("rollupSwapBytes"), 16384)
        self.assertNotIn("12340000", str(value))

    def test_rejects_incomplete_duplicate_or_inconsistent_rollup_numbers(self):
        for text in (ROLLUP.replace("Swap:                 16 kB\n", ""),
                     ROLLUP + "Rss: 4096 kB\n", ROLLUP.replace("Rss:                4096", "Rss:                4097"),
                     ROLLUP.replace("Swap:                 16 kB", "Swap:                 -1 kB"),
                     ROLLUP.replace("Pss_Anon:           2048 kB\n", "")):
            with self.subTest(text=text), self.assertRaises(ValueError): parse_linux_rollup(text)

    def test_old_kernel_without_proportional_groups_keeps_groups_unreported(self):
        text = "\n".join(line for line in ROLLUP.splitlines() if not line.startswith(("Pss_Anon:", "Pss_File:", "Pss_Shmem:")))
        value = parse_linux_rollup(text)
        self.assertEqual(value.get("rollupPrivateDirtyBytes"), 1572864)
        self.assertIsNone(value.get("rollupPssAnonBytes"))

    def base(self, elapsed=0, phase="running"):
        return dict(phase=phase, pid=1234, elapsedSeconds=elapsed, nativeUncategorizedCommittedBytes=70000000)

    def test_binds_signed_numeric_file_to_base_identity_and_keeps_unreported_values_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory-details.csv"
            base = self.base()
            row = make_details_sample(base, parse_nmt_details(NMT, 1234), parse_linux_rollup(ROLLUP))
            self.assertEqual(row.get("nativeDetailRemainderBytes"), 11107712)
            append_details_sample(path, row)
            receipt = details_receipt(path, 1)
            self.assertEqual(receipt, dict(version=1, file="memory-details.csv", samples=1,
                sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
            result = verify_details_file(path, receipt, [base], "linux")
            self.assertEqual(result, [row])
            with path.open(newline="") as source: raw = next(csv.DictReader(source))
            self.assertEqual(raw["nativeCompilerCommittedBytes"], "")

    def test_rejects_altered_bytes_and_validly_resigned_rows_from_other_pid_or_time(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory-details.csv"
            base = self.base()
            row = make_details_sample(base, parse_nmt_details(NMT, 1234), parse_linux_rollup(ROLLUP))
            append_details_sample(path, row)
            receipt = details_receipt(path, 1)
            path.write_bytes(path.read_bytes().replace(b"2359296", b"2359297"))
            with self.assertRaises(ValueError): verify_details_file(path, receipt, [base], "linux")
            for changed in (dict(row, pid=4321), dict(row, elapsedSeconds=1)):
                path.unlink(); append_details_sample(path, changed)
                with self.assertRaises(ValueError): verify_details_file(path, details_receipt(path, 1), [base], "linux")

    def test_requires_exact_row_coverage_and_platform_measurements(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory-details.csv"
            base = self.base()
            row = make_details_sample(base, parse_nmt_details(NMT, 1234), parse_linux_rollup(ROLLUP))
            append_details_sample(path, row)
            receipt = details_receipt(path, 1)
            with self.assertRaises(ValueError): verify_details_file(path, receipt, [base, self.base(1)], "linux")
            with self.assertRaises(ValueError): verify_details_file(path, receipt, [base], "windows")

    def test_windows_uses_explicitly_uncollected_linux_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory-details.csv"
            base = self.base()
            row = make_details_sample(base, parse_nmt_details(NMT, 1234), None)
            append_details_sample(path, row)
            receipt = details_receipt(path, 1)
            self.assertEqual(verify_details_file(path, receipt, [base], "windows"), [row])
            with self.assertRaises(ValueError): verify_details_file(path, receipt, [base], "linux")

    def test_rejects_inconsistent_remainder_and_unknown_manifest_version_or_path(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "memory-details.csv"
            base = self.base()
            row = make_details_sample(base, parse_nmt_details(NMT, 1234), parse_linux_rollup(ROLLUP))
            append_details_sample(path, dict(row, nativeDetailRemainderBytes=0))
            receipt = details_receipt(path, 1)
            with self.assertRaises(ValueError): verify_details_file(path, receipt, [base], "linux")
            path.unlink(); append_details_sample(path, row); receipt = details_receipt(path, 1)
            for changed in (dict(receipt, version=2), dict(receipt, version=True), dict(receipt, file="../secrets")):
                with self.assertRaises(ValueError): verify_details_file(path, changed, [base], "linux")

    def test_requires_actual_metaspace_and_symbol_reports_for_new_details(self):
        with self.assertRaises(ValueError):
            make_details_sample(self.base(), parse_nmt_details("1234:\n", 1234), parse_linux_rollup(ROLLUP))

    def test_growth_includes_closed_samples_and_never_invents_unreported_measurements(self):
        rows = [dict(phase="running", elapsedSeconds=0, x=1000, y=None),
            dict(phase="running", elapsedSeconds=120, x=10, y=4),
            dict(phase="running", elapsedSeconds=150, x=12, y=5),
            dict(phase="closed", elapsedSeconds=180, x=20, y=None)]
        self.assertEqual(detail_growth(rows, 120, "x"), 10)
        self.assertIsNone(detail_growth(rows, 120, "y"))

if __name__ == "__main__": unittest.main()
