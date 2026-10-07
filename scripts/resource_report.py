#!/usr/bin/env python3
"""Replay numeric resource samples and reject incomplete or inconsistent acceptance receipts."""
import argparse
import csv
import json
import math
from pathlib import Path
import re

from http_resource_contract import HTTP_COMPONENTS, NATIVE_BREAKDOWN
from resource_soak import MIB, parse_workload_result, summarize
from resource_memory_details import verify_details_file, detail_growth
from resource_native_trim import verify_trim_file

FIELDS = {"phase", "pid", "elapsedSeconds", "nativeReservedBytes", "nativeCommittedBytes",
    "javaHeapCommittedBytes", "nativeNonHeapCommittedBytes", "nativeThreadCount", "heapUsedBytes",
    "rssBytes", "privateBytes"}


def read_samples(csv_path, operating_system):
    rows = []
    with csv_path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        columns = set(reader.fieldnames or [])
        if reader.fieldnames is None or len(reader.fieldnames) != len(columns) or columns not in (FIELDS, FIELDS | NATIVE_BREAKDOWN):
            raise ValueError("Unexpected numeric CSV columns")
        for raw in reader:
            if set(raw) != columns or any(value is None for value in raw.values()):
                raise ValueError("Incomplete numeric CSV row")
            row = {"phase": raw["phase"], "elapsedSeconds": float(raw["elapsedSeconds"])}
            for key in columns - {"phase", "elapsedSeconds"}:
                value = raw[key]
                if key == "privateBytes" and value == "" and operating_system == "linux":
                    row[key] = None
                elif re.fullmatch(r"[0-9]+", value):
                    row[key] = int(value)
                else:
                    raise ValueError("Missing or invalid numeric CSV value: " + key)
            if row["phase"] not in ("running", "closed") or not math.isfinite(row["elapsedSeconds"]):
                raise ValueError("Invalid sample phase or time")
            if row["pid"] <= 0 or row["nativeThreadCount"] <= 0 or row["rssBytes"] <= 0:
                raise ValueError("Missing process or native thread measurements")
            if not row["nativeReservedBytes"] >= row["nativeCommittedBytes"] >= row["javaHeapCommittedBytes"] > 0:
                raise ValueError("Inconsistent NMT totals")
            if row["nativeNonHeapCommittedBytes"] != row["nativeCommittedBytes"] - row["javaHeapCommittedBytes"]:
                raise ValueError("NMT non-heap commit does not match total minus Java heap")
            if NATIVE_BREAKDOWN <= columns and sum(row[key] for key in NATIVE_BREAKDOWN) != row["nativeNonHeapCommittedBytes"]:
                raise ValueError("NMT categories do not match non-heap total")
            if operating_system == "windows" and row["privateBytes"] <= 0:
                raise ValueError("Missing Windows private commit")
            if operating_system == "linux" and row["privateBytes"] is not None:
                raise ValueError("Linux private commit was not collected")
            rows.append(row)
    if not rows or rows[-1]["phase"] != "closed" or any(row["phase"] != "running" for row in rows[:-1]):
        raise ValueError("Expected running samples followed by one closed-phase sample")
    return rows


def verify_dataset(summary_path: Path, source_commit: str, seconds: int):
    if re.fullmatch(r"[0-9a-f]{40}", source_commit) is None or type(seconds) is not int or not 10 <= seconds <= 7200:
        raise ValueError("Expected an exact source commit and supported duration")
    meta = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(meta, dict) or meta.get("status") != "passed":
        raise ValueError("Workload has no passing receipt")
    if meta.get("sourceCommit") != source_commit or meta.get("sourceTreeDirty") is not False:
        raise ValueError("Wrong source commit or modified source tree")
    if meta.get("component") not in ("agent", "starter", *HTTP_COMPONENTS) or meta.get("operatingSystem") not in ("linux", "windows"):
        raise ValueError("Unsupported component or operating system")
    if "httpTiming" in meta and (type(meta["httpTiming"]) is not bool or meta["component"] not in HTTP_COMPONENTS):
        raise ValueError("Invalid HTTP timing profile")
    if "nativeHeapTrim" in meta and (meta["component"] not in HTTP_COMPONENTS or
            meta["operatingSystem"] != "linux" or "memoryDetails" not in meta):
        raise ValueError("Native heap trim requires Linux HTTP memory details")
    if ("nativeHeapTrim" in meta) != ("workflowRunId" in meta) or ("workflowRunId" in meta and
            meta["workflowRunId"] is not None and (type(meta["workflowRunId"]) is not int or meta["workflowRunId"] <= 0)):
        raise ValueError("Invalid native trim workflow identity")
    rows = read_samples(summary_path.with_name("memory.csv"), meta["operatingSystem"])
    if meta["component"] in HTTP_COMPONENTS and any(not NATIVE_BREAKDOWN <= row.keys() for row in rows):
        raise ValueError("HTTP comparison requires numeric NMT categories")
    replay = summarize(rows, seconds, min(120, seconds / 5), min(30, seconds / 10))
    recorded_replay = dict(replay)
    if "growthIncludesClosed" not in meta:
        # Legacy collectors reported running peaks only. Validate that receipt before
        # returning the stronger replay, which always checks closing memory as well.
        recorded_replay.pop("growthIncludesClosed")
        steady = [row for row in rows if row["phase"] == "running" and row["elapsedSeconds"] >= replay["warmupSeconds"]]
        recorded_replay["postWarmupPeakNativeGrowthBytes"] = max(row["nativeNonHeapCommittedBytes"] for row in steady) - replay["baseline"]["nativeNonHeapCommittedBytes"]
        recorded_replay["postWarmupPeakResidentGrowthBytes"] = max(row["rssBytes"] for row in steady) - replay["baseline"]["rssBytes"]
    elif meta["growthIncludesClosed"] is not True:
        raise ValueError("Unsupported memory growth definition")
    for key, expected in recorded_replay.items():
        if key not in meta or meta[key] != expected:
            raise ValueError("Summary does not match numeric replay: " + key)
    workload = meta.get("workload")
    if not isinstance(workload, dict) or any(not isinstance(key, str) or
        (type(value) is not int and not (key in ("samplerStopped", "executorsStopped", "servletStopped", "healthyConnectionsReused", "faultConnectionsClosed") and value is True)) for key, value in workload.items()):
        raise ValueError("Invalid workload counters")
    if any(type(value) is int and value < 0 for value in workload.values()):
        raise ValueError("Negative workload counters")
    text = "RESOURCE_RESULT " + meta["component"] + " " + " ".join(
        key + "=" + (str(value).lower() if isinstance(value, bool) else str(value)) for key, value in workload.items())
    if parse_workload_result(text, meta["component"], seconds) != workload:
        raise ValueError("Invalid workload or cleanup receipt")
    result = {**meta, **replay}
    if "memoryDetails" in meta:
        if any(not NATIVE_BREAKDOWN <= row.keys() for row in rows):
            raise ValueError("Memory details require the matching base NMT categories")
        result["_memoryDetailsRows"] = verify_details_file(summary_path.with_name("memory-details.csv"),
            meta["memoryDetails"], rows, meta["operatingSystem"])
    if "nativeHeapTrim" in meta:
        result["_nativeTrimRows"] = verify_trim_file(summary_path.with_name("native-heap-trim.csv"),
            meta["nativeHeapTrim"], rows[-1], result["_memoryDetailsRows"][-1], meta["operatingSystem"])
    return result, rows


def verify_matrix(summary_paths, source_commit, seconds, workload="components"):
    datasets = [verify_dataset(Path(summary), source_commit, seconds) for summary in summary_paths]
    identities = [(meta["component"], meta["operatingSystem"]) for meta, rows in datasets]
    if workload not in ("components", "http"):
        raise ValueError("Unsupported workload matrix")
    components = HTTP_COMPONENTS if workload == "http" else ("agent", "starter")
    expected = {(component, system) for component in components for system in ("linux", "windows")}
    if len(identities) != len(expected) or set(identities) != expected:
        raise ValueError("Expected each workload mode exactly once on Linux and Windows")
    if workload == "http" and len({meta["workload"].get("connectionPolicy", 1) for meta, rows in datasets}) != 1:
        raise ValueError("HTTP matrix mixes connection policies")
    if len({"memoryDetails" in meta for meta, rows in datasets}) != 1:
        raise ValueError("Resource matrix mixes detailed and legacy memory profiles")
    if len({meta.get("httpTiming", False) for meta, rows in datasets}) != 1:
        raise ValueError("Resource matrix mixes timing profiles")
    if len({"nativeHeapTrim" in meta for meta, rows in datasets}) != 1:
        raise ValueError("Resource matrix mixes native trim profiles")
    return sorted(datasets, key=lambda dataset: (dataset[0]["component"], dataset[0]["operatingSystem"]))


def render_table(datasets):
    if datasets and datasets[0][0]["component"] in HTTP_COMPONENTS:
        return render_http_table(datasets) + render_memory_details(datasets)
    lines = ["| Component | OS | Running samples | Cycles | GC retained heap delta (MiB) | NMT non-heap growth (MiB) | RSS growth (MiB) | Native threads (peak / closed) |",
        "|---|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        workload, closed = meta["workload"], meta["closedSample"]
        retained = (workload["finalHeap"] - workload["baselineHeap"]) / MIB
        lines.append(f"| {meta['component']} | {meta['operatingSystem']} | {meta['runningSamples']} | {workload['cycles']} | {retained:.2f} | "
            f"{meta['postWarmupPeakNativeGrowthBytes'] / MIB:.2f} | {meta['postWarmupPeakResidentGrowthBytes'] / MIB:.2f} | "
            f"{meta['peakNativeThreadCount']} / {closed['nativeThreadCount']} |")
    return "\n".join(lines) + "\n" + render_memory_details(datasets)

def render_memory_details(datasets):
    if not datasets or not all("memoryDetails" in meta for meta, rows in datasets): return ""
    keys = ("nativeArenaChunkCommittedBytes", "nativeMetaspaceCommittedBytes", "nativeCompilerCommittedBytes",
        "nativeInternalCommittedBytes", "nativeSymbolCommittedBytes", "nativeTrackingCommittedBytes")
    lines = ["", "| Component | OS | Arena Chunk growth | Metaspace growth | Compiler growth | Internal growth | Symbol growth | NMT tracking growth |",
        "|---|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        details = meta["_memoryDetailsRows"]
        values = [detail_growth(details, meta["warmupSeconds"], key) for key in keys]
        lines.append(f"| {meta['component']} | {meta['operatingSystem']} | " + " | ".join(
            "not reported" if value is None else f"{value / MIB:.2f}" for value in values) + " |")
    lines += ["", "| Component | Linux rollup RSS growth | Anonymous resident growth | PSS growth | Anonymous PSS growth | File PSS growth | Shared-memory PSS growth |",
        "|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        if meta["operatingSystem"] != "linux": continue
        values = [detail_growth(meta["_memoryDetailsRows"], meta["warmupSeconds"], key) for key in
            ("rollupRssBytes", "rollupAnonymousBytes", "rollupPssBytes", "rollupPssAnonBytes", "rollupPssFileBytes", "rollupPssShmemBytes")]
        lines.append(f"| {meta['component']} | " + " | ".join(
            "not reported" if value is None else f"{value / MIB:.2f}" for value in values) + " |")
    lines += ["", "Values are MiB, including closing samples. Unreported categories stay unreported. "
        "NMT committed allocation, RSS residency and proportional shares measure different quantities; "
        "serial readings are not atomic, and category peaks cannot be summed to attribute a total peak."]
    return "\n".join(lines) + "\n"


def render_http_table(datasets):
    lines = ["| Starter | OS | Requests | Observed requests / timeouts | Final minute requests / timeouts | GC retained heap delta (MiB) | NMT non-heap growth (MiB) | RSS growth (MiB) |",
        "|---|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        workload = meta["workload"]
        lines.append(f"| {meta['component'].removeprefix('http-')} | {meta['operatingSystem']} | {workload['requests']} | "
            f"{workload['observedRequests']} / {workload['observedTimeouts']} | {workload['windowRequests']} / {workload['windowTimeouts']} | "
            f"{(workload['finalHeap'] - workload['baselineHeap']) / MIB:.2f} | "
            f"{meta['postWarmupPeakNativeGrowthBytes'] / MIB:.2f} | {meta['postWarmupPeakResidentGrowthBytes'] / MIB:.2f} |")
    policy = datasets[0][0]["workload"].get("connectionPolicy", 1)
    lines += ["", "Connection policy: " + ("fault-close with verified healthy connection reuse." if policy == 2 else "original shared pool."), "", "All measurements include the JUnit driver, Servlet and loopback downstream in one JVM. "
        "Separate CI runners and GC timing prevent attributing paired differences solely to Starter.", "",
        "| Starter | OS | Class growth | Thread growth | Code growth | GC growth | Other growth | Remaining NMT growth |",
        "|---|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        steady = [row for row in rows if row["phase"] == "closed" or row["elapsedSeconds"] >= meta["warmupSeconds"]]
        growth = [(max(row[key] for row in steady) - meta["baseline"][key]) / MIB for key in
            ("nativeClassCommittedBytes", "nativeThreadCommittedBytes", "nativeCodeCommittedBytes", "nativeGCCommittedBytes",
             "nativeOtherCommittedBytes", "nativeUncategorizedCommittedBytes")]
        lines.append(f"| {meta['component'].removeprefix('http-')} | {meta['operatingSystem']} | " +
            " | ".join(f"{value:.2f}" for value in growth) + " |")
    lines += ["", "NMT category peaks are independent; their growth values do not sum to a simultaneous peak. Values are MiB."]
    if datasets[0][0].get("httpTiming", False):
        lines += ["", "Optional phase timing is enabled; measurements include the bounded observer."]
    return "\n".join(lines) + "\n"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Downloaded artifact directory containing the four numeric receipts")
    parser.add_argument("--source-commit", required=True, help="Exact commit from the completed GitHub workflow")
    parser.add_argument("--workload", choices=("components", "http"), default="components")
    parser.add_argument("--seconds", type=int, default=3600)
    parser.add_argument("--output", type=Path, help="Optional Markdown table; written only after the full matrix passes")
    args = parser.parse_args()
    try:
        datasets = verify_matrix(args.directory.rglob("summary.json"), args.source_commit, args.seconds, args.workload)
        table = render_table(datasets)
        if args.output is not None:
            args.output.write_text(table, encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, "Resource acceptance rejected: " + str(error) + "\n")
    print(table, end="")


if __name__ == "__main__":
    main()
