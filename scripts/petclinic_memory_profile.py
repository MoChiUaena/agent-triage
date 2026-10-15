"""Capture numeric JVM and Linux memory details from one owned Petclinic process."""
import csv
import hashlib
import json
import math
import re
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
SUMMARY_FIELDS = {"profileVersion", "sourceCommit", "upstreamRevision", "durationSeconds",
    "starterEnabled", "sampleCount", "costSummarySha256", "profileCsvSha256"}
BYTE_FIELDS = tuple(field for field in FIELDS if field.endswith("Bytes"))


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


def names(starter_enabled):
    return ("profile.csv", "profile-summary.json") if starter_enabled else (
        "control-profile.csv", "control-profile-summary.json")


def checked_rows(rows, seconds):
    if len(rows) != 2 or [row.get("phase") for row in rows] != ["before", "after"] \
            or any(set(row) != set(FIELDS) for row in rows):
        raise ValueError("Expected two complete numeric memory phases")
    parsed = []
    for row in rows:
        value = {"phase": row["phase"]}
        for field in ("pid", "nativeThreadCount", *BYTE_FIELDS):
            raw = str(row[field])
            if re.fullmatch(r"[0-9]+", raw) is None:
                raise ValueError("Non-numeric memory field")
            value[field] = int(raw)
        for field in ("elapsedSeconds", "captureDurationMs"):
            value[field] = float(row[field])
            if not math.isfinite(value[field]) or value[field] < 0:
                raise ValueError("Invalid memory profile timing")
        if value["pid"] <= 0 or value["nativeThreadCount"] <= 0 \
                or value["rssBeforeBytes"] <= 0 or value["rssAfterBytes"] <= 0 \
                or value["heapUsedBytes"] <= 0 or value["javaHeapCommittedBytes"] < value["heapUsedBytes"] \
                or value["anonymousBeforeBytes"] > value["rssBeforeBytes"] \
                or value["anonymousAfterBytes"] > value["rssAfterBytes"] \
                or value["privateBeforeBytes"] > value["rssBeforeBytes"] \
                or value["privateAfterBytes"] > value["rssAfterBytes"]:
            raise ValueError("Inconsistent process memory profile")
        categories = sum(value[key] for key in ("nativeClassCommittedBytes", "nativeThreadCommittedBytes",
            "nativeCodeCommittedBytes", "nativeGCCommittedBytes", "nativeOtherCommittedBytes",
            "nativeUncategorizedCommittedBytes"))
        if categories != value["nativeNonHeapCommittedBytes"]:
            raise ValueError("NMT categories do not match non-heap commit")
        parsed.append(value)
    if parsed[0]["pid"] != parsed[1]["pid"] or not 0 <= parsed[0]["elapsedSeconds"] \
            < parsed[1]["elapsedSeconds"] <= seconds + 120:
        raise ValueError("Memory phases changed JVM or duration")
    return parsed


def write_bundle(folder, rows, source_commit, upstream_revision, seconds, cost_summary_path, starter_enabled):
    checked_rows(rows, seconds)
    if re.fullmatch(r"[a-f0-9]{40}", source_commit) is None \
            or re.fullmatch(r"[a-f0-9]{40}", upstream_revision) is None:
        raise ValueError("Memory profile needs fixed source identities")
    csv_name, summary_name = names(starter_enabled)
    folder = Path(folder)
    with (folder / csv_name).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    summary = dict(profileVersion=2, sourceCommit=source_commit, upstreamRevision=upstream_revision,
        durationSeconds=seconds, starterEnabled=starter_enabled, sampleCount=2,
        costSummarySha256=hashlib.sha256(Path(cost_summary_path).read_bytes()).hexdigest(),
        profileCsvSha256=hashlib.sha256((folder / csv_name).read_bytes()).hexdigest())
    (folder / summary_name).write_text(json.dumps(summary, indent=2, sort_keys=True), encoding="utf-8")


def verify_bundle(folder, source_commit, upstream_revision, seconds, cost_sha, profile_sha, starter_enabled):
    folder = Path(folder)
    csv_name, summary_name = names(starter_enabled)
    matches = list(folder.rglob(summary_name))
    if len(matches) != 1 or re.fullmatch(r"[a-f0-9]{64}", profile_sha) is None \
            or hashlib.sha256(matches[0].read_bytes()).hexdigest() != profile_sha:
        raise ValueError("Memory profile summary does not match its trusted SHA-256")
    summary = json.loads(matches[0].read_text(encoding="utf-8"))
    if not isinstance(summary, dict) or set(summary) != SUMMARY_FIELDS \
            or summary["profileVersion"] not in (1, 2) or summary["sampleCount"] != 2 \
            or summary["sourceCommit"] != source_commit or summary["upstreamRevision"] != upstream_revision \
            or summary["durationSeconds"] != seconds or summary["starterEnabled"] is not starter_enabled \
            or summary["costSummarySha256"] != cost_sha:
        raise ValueError("Memory profile does not match the completed cost run")
    csv_path = matches[0].with_name(csv_name)
    if re.fullmatch(r"[a-f0-9]{64}", summary["profileCsvSha256"]) is None \
            or hashlib.sha256(csv_path.read_bytes()).hexdigest() != summary["profileCsvSha256"]:
        raise ValueError("Memory profile CSV does not match its SHA-256")
    with csv_path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != list(FIELDS):
            raise ValueError("Unexpected memory profile columns")
        rows = checked_rows(list(reader), seconds)
    before, after = rows
    cost_csv = matches[0].with_name("cost.csv" if starter_enabled else "control-cost.csv")
    with cost_csv.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        cost_rows = list(reader)
        if reader.fieldnames != ["pid", "elapsedSeconds", "rssBytes", "privateBytes"] \
                or {row["pid"] for row in cost_rows} != {str(before["pid"])}:
            raise ValueError("Memory profile belongs to a different cost JVM")
    if summary["profileVersion"] == 2:
        steady = next((row for row in cost_rows if float(row["elapsedSeconds"]) >= seconds / 5), None)
        if steady is None or abs(before["elapsedSeconds"] - float(steady["elapsedSeconds"])) > 5 \
                or abs(after["elapsedSeconds"] - float(cost_rows[-1]["elapsedSeconds"])) > 5 \
                or max(row["captureDurationMs"] for row in rows) > 5000:
            raise ValueError("Memory profile is not aligned with warmup and closing cost samples")
    result = dict(pid=before["pid"], rssDeltaBytes=after["rssAfterBytes"] - before["rssAfterBytes"],
        anonymousDeltaBytes=after["anonymousAfterBytes"] - before["anonymousAfterBytes"],
        heapUsedDeltaBytes=after["heapUsedBytes"] - before["heapUsedBytes"],
        heapCommittedDeltaBytes=after["javaHeapCommittedBytes"] - before["javaHeapCommittedBytes"],
        nativeNonHeapDeltaBytes=after["nativeNonHeapCommittedBytes"] - before["nativeNonHeapCommittedBytes"],
        threadCountDelta=after["nativeThreadCount"] - before["nativeThreadCount"],
        maxCaptureDurationMs=max(row["captureDurationMs"] for row in rows),
        maxCaptureRssDriftBytes=max(abs(row["rssAfterBytes"] - row["rssBeforeBytes"]) for row in rows),
        summarySha256=profile_sha)
    for label, field in (("nativeClassDeltaBytes", "nativeClassCommittedBytes"),
                         ("nativeThreadCommittedDeltaBytes", "nativeThreadCommittedBytes"),
                         ("nativeCodeDeltaBytes", "nativeCodeCommittedBytes"),
                         ("nativeGcDeltaBytes", "nativeGCCommittedBytes"),
                         ("nativeOtherDeltaBytes", "nativeOtherCommittedBytes"),
                         ("nativeUncategorizedDeltaBytes", "nativeUncategorizedCommittedBytes")):
        result[label] = after[field] - before[field]
    return result
