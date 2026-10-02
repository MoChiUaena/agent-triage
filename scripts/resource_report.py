#!/usr/bin/env python3
"""Replay numeric resource samples and reject incomplete or inconsistent acceptance receipts."""
import argparse
import csv
import json
import math
from pathlib import Path
import re

from resource_soak import MIB, parse_workload_result, summarize

FIELDS = {"phase", "pid", "elapsedSeconds", "nativeReservedBytes", "nativeCommittedBytes",
    "javaHeapCommittedBytes", "nativeNonHeapCommittedBytes", "nativeThreadCount", "heapUsedBytes",
    "rssBytes", "privateBytes"}


def read_samples(csv_path, operating_system):
    rows = []
    with csv_path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None or len(reader.fieldnames) != len(FIELDS) or set(reader.fieldnames) != FIELDS:
            raise ValueError("Unexpected numeric CSV columns")
        for raw in reader:
            if set(raw) != FIELDS or any(value is None for value in raw.values()):
                raise ValueError("Incomplete numeric CSV row")
            row = {"phase": raw["phase"], "elapsedSeconds": float(raw["elapsedSeconds"])}
            for key in FIELDS - {"phase", "elapsedSeconds"}:
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
    if meta.get("component") not in ("agent", "starter") or meta.get("operatingSystem") not in ("linux", "windows"):
        raise ValueError("Unsupported component or operating system")
    rows = read_samples(summary_path.with_name("memory.csv"), meta["operatingSystem"])
    replay = summarize(rows, seconds, min(120, seconds / 5), min(30, seconds / 10))
    for key, expected in replay.items():
        if key not in meta or meta[key] != expected:
            raise ValueError("Summary does not match numeric replay: " + key)
    workload = meta.get("workload")
    if not isinstance(workload, dict) or any(not isinstance(key, str) or
        (type(value) is not int and not (key == "samplerStopped" and value is True)) for key, value in workload.items()):
        raise ValueError("Invalid workload counters")
    if any(type(value) is int and value < 0 for value in workload.values()):
        raise ValueError("Negative workload counters")
    text = "RESOURCE_RESULT " + meta["component"] + " " + " ".join(
        key + "=" + (str(value).lower() if isinstance(value, bool) else str(value)) for key, value in workload.items())
    if parse_workload_result(text, meta["component"], seconds) != workload:
        raise ValueError("Invalid workload or cleanup receipt")
    return meta, rows


def verify_matrix(summary_paths, source_commit, seconds):
    datasets = [verify_dataset(Path(summary), source_commit, seconds) for summary in summary_paths]
    identities = [(meta["component"], meta["operatingSystem"]) for meta, rows in datasets]
    expected = {(component, system) for component in ("agent", "starter") for system in ("linux", "windows")}
    if len(identities) != len(expected) or set(identities) != expected:
        raise ValueError("Expected exactly one Agent and Starter receipt on each of Linux and Windows")
    return sorted(datasets, key=lambda dataset: (dataset[0]["component"], dataset[0]["operatingSystem"]))


def render_table(datasets):
    lines = ["| Component | OS | Running samples | Cycles | GC retained heap delta (MiB) | NMT non-heap growth (MiB) | RSS growth (MiB) | Native threads (peak / closed) |",
        "|---|---|---:|---:|---:|---:|---:|---:|"]
    for meta, rows in datasets:
        workload, closed = meta["workload"], meta["closedSample"]
        retained = (workload["finalHeap"] - workload["baselineHeap"]) / MIB
        lines.append(f"| {meta['component']} | {meta['operatingSystem']} | {meta['runningSamples']} | {workload['cycles']} | {retained:.2f} | "
            f"{meta['postWarmupPeakNativeGrowthBytes'] / MIB:.2f} | {meta['postWarmupPeakResidentGrowthBytes'] / MIB:.2f} | "
            f"{meta['peakNativeThreadCount']} / {closed['nativeThreadCount']} |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Downloaded artifact directory containing the four numeric receipts")
    parser.add_argument("--source-commit", required=True, help="Exact commit from the completed GitHub workflow")
    parser.add_argument("--seconds", type=int, default=3600)
    parser.add_argument("--output", type=Path, help="Optional Markdown table; written only after the full matrix passes")
    args = parser.parse_args()
    try:
        datasets = verify_matrix(args.directory.rglob("summary.json"), args.source_commit, args.seconds)
        table = render_table(datasets)
        if args.output is not None:
            args.output.write_text(table, encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError) as error:
        parser.exit(1, "Resource acceptance rejected: " + str(error) + "\n")
    print(table, end="")


if __name__ == "__main__":
    main()
