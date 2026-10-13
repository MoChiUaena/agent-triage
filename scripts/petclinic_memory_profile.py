"""Capture numeric JVM and Linux memory details from one owned Petclinic process."""
import subprocess
import time
from pathlib import Path

from resource_memory_details import parse_linux_rollup
from resource_soak import parse_heap, parse_native_memory


FIELDS = ("phase", "pid", "elapsedSeconds", "captureDurationMs", "rssBeforeBytes", "rssAfterBytes",
    "anonymousBeforeBytes", "anonymousAfterBytes", "privateBeforeBytes", "privateAfterBytes",
    "heapUsedBytes", "javaHeapCommittedBytes", "nativeNonHeapCommittedBytes", "nativeThreadCount",
    "nativeClassCommittedBytes", "nativeThreadCommittedBytes", "nativeCodeCommittedBytes",
    "nativeGCCommittedBytes", "nativeOtherCommittedBytes", "nativeUncategorizedCommittedBytes")


def capture(pid, phase, jcmd, origin):
    if pid <= 0 or phase not in ("before", "after"):
        raise ValueError("Memory profile requires one owned JVM and a known phase")
    started = time.monotonic()
    rollup_path = Path(f"/proc/{pid}/smaps_rollup")
    before = parse_linux_rollup(rollup_path.read_text(encoding="ascii"))
    native = parse_native_memory(subprocess.check_output(
        [str(jcmd), str(pid), "VM.native_memory", "summary", "scale=KB"], text=True, timeout=10),
        pid, require_breakdown=True)
    heap = parse_heap(subprocess.check_output([str(jcmd), str(pid), "GC.heap_info"], text=True, timeout=10), pid)
    after = parse_linux_rollup(rollup_path.read_text(encoding="ascii"))
    finished = time.monotonic()
    return dict(phase=phase, pid=pid, elapsedSeconds=round(started - origin, 3),
        captureDurationMs=round((finished - started) * 1000, 3),
        rssBeforeBytes=before["rollupRssBytes"], rssAfterBytes=after["rollupRssBytes"],
        anonymousBeforeBytes=before["rollupAnonymousBytes"],
        anonymousAfterBytes=after["rollupAnonymousBytes"],
        privateBeforeBytes=before["rollupPrivateCleanBytes"] + before["rollupPrivateDirtyBytes"],
        privateAfterBytes=after["rollupPrivateCleanBytes"] + after["rollupPrivateDirtyBytes"],
        heapUsedBytes=heap, javaHeapCommittedBytes=native["javaHeapCommittedBytes"],
        nativeNonHeapCommittedBytes=native["nativeNonHeapCommittedBytes"],
        nativeThreadCount=native["nativeThreadCount"],
        nativeClassCommittedBytes=native["nativeClassCommittedBytes"],
        nativeThreadCommittedBytes=native["nativeThreadCommittedBytes"],
        nativeCodeCommittedBytes=native["nativeCodeCommittedBytes"],
        nativeGCCommittedBytes=native["nativeGCCommittedBytes"],
        nativeOtherCommittedBytes=native["nativeOtherCommittedBytes"],
        nativeUncategorizedCommittedBytes=native["nativeUncategorizedCommittedBytes"])
