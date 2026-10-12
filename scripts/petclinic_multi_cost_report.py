#!/usr/bin/env python3
"""Recheck the numeric-only Petclinic V5 cost artifact before comparing runs."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re

from petclinic_multi_cost import REQUEST_COLUMNS
from prepare_petclinic_rest import REVISION

SUMMARY_FIELDS_V1 = {"durationSeconds", "completedRequests", "observedRps", "warmupSeconds",
    "requestP50Ms", "requestP95Ms", "requestMaxMs", "sampleCount", "maxStartLagMs",
    "maxInterStartGapMs", "baselineRssBytes", "peakRssBytes", "rssPeakGrowthBytes",
    "finalRssBytes", "baselinePrivateBytes", "privatePeakGrowthBytes", "sourceCommit",
    "upstreamRevision", "observedRequests", "inventoryTimeouts", "payment2xx",
    "payment5xx", "agentValidated"}
SUMMARY_FIELDS_V2 = SUMMARY_FIELDS_V1 | {"artifactVersion", "requestsCsvSha256", "memoryCsvSha256"}


def rows(path, fields):
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(fields):
            raise ValueError("Unexpected numeric CSV columns")
        result = list(reader)
    if not result or any(set(row) != set(fields) or any(value is None or value == "" for value in row.values())
                         for row in result):
        raise ValueError("Missing numeric CSV row")
    return result


def check_close(actual, expected, tolerance=.01):
    if not math.isfinite(actual) or not math.isfinite(expected) or abs(actual - expected) > tolerance:
        raise ValueError("Numeric cost summary does not match its CSV")


def verify(folder, source_commit, seconds, expected_summary_sha=None, trust_current_run=False,
           legacy_requests_sha=None, legacy_memory_sha=None):
    matches = list(folder.rglob("cost-summary.json"))
    if len(matches) != 1:
        raise ValueError("Expected exactly one cost summary")
    summary_path = matches[0]
    summary_sha = hashlib.sha256(summary_path.read_bytes()).hexdigest()
    if trust_current_run == (expected_summary_sha is not None):
        raise ValueError("Specify either a trusted summary SHA-256 or the current-run trust mode")
    if expected_summary_sha is not None and (re.fullmatch(r"[a-f0-9]{64}", expected_summary_sha) is None
                                             or summary_sha != expected_summary_sha):
        raise ValueError("Cost summary does not match the trusted SHA-256")
    data = json.loads(summary_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or set(data) not in (SUMMARY_FIELDS_V1, SUMMARY_FIELDS_V2):
        raise ValueError("Unexpected cost summary fields")
    if not re.fullmatch(r"[a-f0-9]{40}", source_commit) or data.get("sourceCommit") != source_commit \
            or data.get("upstreamRevision") != REVISION or data.get("durationSeconds") != seconds \
            or data.get("agentValidated") is not True:
        raise ValueError("Cost artifact belongs to a different source or incomplete Agent run")
    expected = seconds * 2
    if data.get("completedRequests") != expected or data.get("observedRequests") != expected + 2 \
            or data.get("inventoryTimeouts") != expected + 2 \
            or data.get("payment2xx") != 1 + (expected + 1) // 2 \
            or data.get("payment5xx") != 1 + expected // 2:
        raise ValueError("Incomplete or inconsistent V5 workload counts")

    requests_path = summary_path.with_name("requests.csv")
    samples_path = summary_path.with_name("cost.csv")
    if set(data) == SUMMARY_FIELDS_V2 and data["artifactVersion"] == 2:
        expected_requests_sha, expected_memory_sha = data["requestsCsvSha256"], data["memoryCsvSha256"]
    elif set(data) == SUMMARY_FIELDS_V1 and legacy_requests_sha and legacy_memory_sha:
        expected_requests_sha, expected_memory_sha = legacy_requests_sha, legacy_memory_sha
    else:
        raise ValueError("Legacy cost artifacts need both documented CSV hashes")
    for path, expected_hash in ((requests_path, expected_requests_sha), (samples_path, expected_memory_sha)):
        if not isinstance(expected_hash, str) or re.fullmatch(r"[a-f0-9]{64}", expected_hash) is None \
                or hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
            raise ValueError("Numeric cost CSV does not match its expected SHA-256")
    requests = rows(requests_path, REQUEST_COLUMNS)
    samples = rows(samples_path, ("pid", "elapsedSeconds", "rssBytes", "privateBytes"))
    if len(requests) != expected or len(samples) != data.get("sampleCount") or len(samples) < 5:
        raise ValueError("Numeric cost rows are incomplete")
    starts, lags, durations = [], [], []
    for index, row in enumerate(requests):
        if int(row["index"]) != index:
            raise ValueError("Request order changed")
        scheduled, started = float(row["scheduledElapsedSeconds"]), float(row["startedElapsedSeconds"])
        lag, duration = float(row["startLagMs"]), float(row["durationMs"])
        if not all(math.isfinite(value) for value in (scheduled, started, lag, duration)) \
                or started < scheduled - .001:
            raise ValueError("Non-finite or prematurely started cost request")
        check_close(scheduled, index * .5)
        check_close(lag, max(0, (started - scheduled) * 1000), 1.01)
        if lag < 0 or lag > 250 or duration < 0:
            raise ValueError("Invalid request timing or missed schedule")
        starts.append(started); lags.append(lag); durations.append(duration)
    if any(a >= b for a, b in zip(starts, starts[1:])):
        raise ValueError("Request starts are not increasing")
    ordered = sorted(durations)
    check_close(float(data["requestP50Ms"]), ordered[math.ceil(expected * .5) - 1])
    check_close(float(data["requestP95Ms"]), ordered[math.ceil(expected * .95) - 1])
    check_close(float(data["requestMaxMs"]), ordered[-1])
    check_close(float(data["maxStartLagMs"]), max(lags))
    check_close(float(data["maxInterStartGapMs"]), max(b - a for a, b in zip(starts, starts[1:])) * 1000)
    check_close(float(data["observedRps"]), 2.0)
    check_close(float(data["warmupSeconds"]), seconds / 5)

    pid = samples[0]["pid"]
    parsed = []
    for row in samples:
        if row["pid"] != pid or not row["pid"].isdigit():
            raise ValueError("Cost samples changed JVM")
        elapsed = float(row["elapsedSeconds"])
        rss, private = int(row["rssBytes"]), int(row["privateBytes"])
        if not math.isfinite(elapsed) or elapsed < 0 or rss <= 0 or private <= 0:
            raise ValueError("Invalid process-memory sample")
        parsed.append((elapsed, rss, private))
    if parsed[0][0] > 5 or parsed[-2][0] < seconds \
            or any(b[0] - a[0] > 20 for a, b in zip(parsed, parsed[1:])):
        raise ValueError("Cost memory sampling has a coverage gap")
    steady = [row for row in parsed if seconds / 5 <= row[0] <= seconds + 1]
    if len(steady) < 3:
        raise ValueError("Missing post-warmup cost samples")
    baseline = steady[0]
    for key, expected_value in {
        "baselineRssBytes": baseline[1], "peakRssBytes": max(row[1] for row in steady),
        "rssPeakGrowthBytes": max(row[1] for row in steady) - baseline[1],
        "finalRssBytes": parsed[-1][1], "baselinePrivateBytes": baseline[2],
        "privatePeakGrowthBytes": max(row[2] for row in steady) - baseline[2],
    }.items():
        if data.get(key) != expected_value:
            raise ValueError("Memory summary does not match numeric samples")
    return {"requests": expected, "p95Ms": data["requestP95Ms"],
            "rssGrowthMiB": round(data["rssPeakGrowthBytes"] / 1048576, 2),
            "privateGrowthMiB": round(data["privatePeakGrowthBytes"] / 1048576, 2),
            "summarySha256": summary_sha}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--seconds", type=int, choices=(60, 300), required=True)
    trust = parser.add_mutually_exclusive_group(required=True)
    trust.add_argument("--expected-summary-sha256", help="Digest recorded outside the downloaded artifact")
    trust.add_argument("--trust-current-run", action="store_true", help="Use only for files created in this workflow run")
    parser.add_argument("--legacy-requests-sha256", help="Documented requests.csv hash for a version 1 artifact")
    parser.add_argument("--legacy-memory-sha256", help="Documented cost.csv hash for a version 1 artifact")
    args = parser.parse_args()
    print(json.dumps(verify(args.directory, args.source_commit, args.seconds,
                            args.expected_summary_sha256, args.trust_current_run,
                            args.legacy_requests_sha256, args.legacy_memory_sha256), sort_keys=True))


if __name__ == "__main__":
    main()
