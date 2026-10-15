"""Keep public-project memory snapshots numeric and bound to the owned JVM."""
import subprocess
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from petclinic_memory_profile import capture, write_bundle, verify_bundle
import petclinic_multi_downstream_smoke as smoke


NMT = """1234:
Native Memory Tracking:
Total: reserved=300000KB, committed=180000KB
- Java Heap (reserved=131072KB, committed=98304KB)
- Class (reserved=20000KB, committed=10000KB)
- Thread (reserved=5000KB, committed=1000KB)
    (thread #25)
- Code (reserved=10000KB, committed=5000KB)
- GC (reserved=5000KB, committed=2000KB)
- Other (reserved=5000KB, committed=3000KB)
"""
HEAP = "1234:\ngarbage-first heap total 98304K, used 12000K"
ROLLUP = """private mapping metadata
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


class PetclinicMemoryProfileTest(unittest.TestCase):
    SOURCE = "a" * 40
    UPSTREAM = "b" * 40

    def example(self):
        with mock.patch.object(Path, "read_text", return_value=ROLLUP), \
                mock.patch.object(subprocess, "check_output", side_effect=[NMT, HEAP]):
            return capture(1234, "before", Path("/usr/bin/jcmd"), time.monotonic())

    def test_capture_uses_one_pid_and_emits_only_numeric_memory_fields(self):
        with mock.patch.object(Path, "read_text", return_value=ROLLUP), \
                mock.patch.object(subprocess, "check_output", side_effect=[NMT, HEAP]) as command:
            row = capture(1234, "before", Path("/usr/bin/jcmd"), 0)
        self.assertEqual(command.call_count, 2)
        self.assertTrue(all(call.args[0][1] == "1234" for call in command.call_args_list))
        self.assertEqual(row["pid"], 1234)
        self.assertEqual(row["rssBeforeBytes"], 4096 * 1024)
        self.assertEqual(row["rssAfterBytes"], 4096 * 1024)
        self.assertEqual(row["anonymousAfterBytes"], 2304 * 1024)
        self.assertEqual(row["heapUsedBytes"], 12000 * 1024)
        self.assertEqual(row["javaHeapCommittedBytes"], 98304 * 1024)
        self.assertEqual(row["nativeThreadCount"], 25)
        self.assertNotIn("private mapping metadata", str(row))

    def test_capture_rejects_diagnostics_from_another_jvm(self):
        with mock.patch.object(Path, "read_text", return_value=ROLLUP), \
                mock.patch.object(subprocess, "check_output", return_value=NMT.replace("1234:", "9999:")):
            with self.assertRaisesRegex(ValueError, "different JVM"):
                capture(1234, "before", Path("/usr/bin/jcmd"), 0)

    def test_profile_hook_starts_once_at_first_post_warmup_cost_sample(self):
        with tempfile.TemporaryDirectory() as directory, \
                mock.patch.object(smoke, "capture_memory",
                                  side_effect=lambda pid, phase, jcmd, origin: {"phase": phase, "pid": pid}) as called:
            on_sample, finish = smoke.memory_profile_hook(Path(directory) / "cost.csv", 1234, 60,
                                                           Path("/usr/bin/jcmd"), 0)
            for elapsed in (0, 15, 30):
                on_sample([{"pid": 1234, "elapsedSeconds": elapsed, "rssBytes": 4096,
                            "privateBytes": 2048}])
            self.assertEqual([row["phase"] for row in finish()], ["before", "after"])
            self.assertEqual(called.call_count, 2)

    def test_profile_bundle_replays_same_pid_and_rejects_modified_numeric_file(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            cost = folder / "cost-summary.json"
            cost.write_text('{"cost":"already checked"}', encoding="utf-8")
            cost_csv = folder / "cost.csv"
            cost_csv.write_text("pid,elapsedSeconds,rssBytes,privateBytes\n1234,15,4096,4096\n1234,62,4096,4096\n", encoding="utf-8")
            before = dict(self.example(), elapsedSeconds=15.0)
            after = dict(before, phase="after", elapsedSeconds=62.0,
                         rssBeforeBytes=before["rssBeforeBytes"] + 512,
                         rssAfterBytes=before["rssAfterBytes"] + 1024,
                         nativeCodeCommittedBytes=before["nativeCodeCommittedBytes"] + 1024,
                         nativeUncategorizedCommittedBytes=before["nativeUncategorizedCommittedBytes"] - 1024)
            write_bundle(folder, [before, after], self.SOURCE, self.UPSTREAM, 60, cost, True)
            profile = folder / "profile-summary.json"
            self.assertEqual(json.loads(profile.read_text(encoding="utf-8"))["profileVersion"], 2)
            trusted = hashlib.sha256(profile.read_bytes()).hexdigest()
            cost_hash = hashlib.sha256(cost.read_bytes()).hexdigest()
            result = verify_bundle(folder, self.SOURCE, self.UPSTREAM, 60, cost_hash, trusted, True)
            self.assertEqual(result["rssDeltaBytes"], 1024)
            self.assertEqual(result["nativeCodeDeltaBytes"], 1024)
            self.assertEqual(result["maxCaptureRssDriftBytes"], 512)
            write_bundle(folder, [dict(before, elapsedSeconds=0.0), after], self.SOURCE,
                         self.UPSTREAM, 60, cost, True)
            shifted = hashlib.sha256(profile.read_bytes()).hexdigest()
            with self.assertRaisesRegex(ValueError, "aligned"):
                verify_bundle(folder, self.SOURCE, self.UPSTREAM, 60, cost_hash, shifted, True)
            write_bundle(folder, [before, after], self.SOURCE, self.UPSTREAM, 60, cost, True)
            cost_csv.write_text("pid,elapsedSeconds,rssBytes,privateBytes\n4321,15,4096,4096\n4321,62,4096,4096\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "cost JVM"):
                verify_bundle(folder, self.SOURCE, self.UPSTREAM, 60, cost_hash, trusted, True)
            cost_csv.write_text("pid,elapsedSeconds,rssBytes,privateBytes\n1234,15,4096,4096\n1234,62,4096,4096\n", encoding="utf-8")
            (folder / "profile.csv").write_bytes((folder / "profile.csv").read_bytes() + b"0")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                verify_bundle(folder, self.SOURCE, self.UPSTREAM, 60, cost_hash, trusted, True)
