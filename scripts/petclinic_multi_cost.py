"""Bounded, process-level measurements for the opt-in public V5 workload."""
import csv
import math
from pathlib import Path
import time

from resource_soak import process_memory
from resource_memory_details import parse_linux_rollup


INTERVAL_SECONDS = 15
REQUEST_INTERVAL_SECONDS = .5
MAX_START_LAG_SECONDS = .25
REQUEST_COLUMNS = ("index", "scheduledElapsedSeconds", "startedElapsedSeconds", "startLagMs", "durationMs")


def measure(seconds, pid, invoke, on_sample=None, on_request=None):
    if not 60 <= seconds <= 600 or pid <= 0:
        raise ValueError("Cost probe requires 60..600 seconds and an owned application PID")
    samples, latencies, request_rows = [], [], []
    started = time.monotonic()
    next_sample = started
    next_request = started

    def sample():
        values = process_memory(pid)
        if values["privateBytes"] is None:
            rollup = parse_linux_rollup(Path(f"/proc/{pid}/smaps_rollup").read_text(encoding="ascii"))
            values["privateBytes"] = rollup["rollupPrivateCleanBytes"] + rollup["rollupPrivateDirtyBytes"]
        if values["privateBytes"] is None or values["privateBytes"] <= 0:
            raise ValueError("Missing process private-memory sample")
        samples.append(dict(pid=pid, elapsedSeconds=round(time.monotonic() - started, 3),
            rssBytes=values["rssBytes"], privateBytes=values["privateBytes"]))
        if on_sample is not None:
            on_sample(samples)

    while time.monotonic() - started < seconds:
        now = time.monotonic()
        if now >= next_sample:
            if now - next_sample > 5:
                raise ValueError("Cost sampling missed its scheduled interval")
            sample()
            next_sample += INTERVAL_SECONDS
        if now < next_request:
            time.sleep(min(.1, next_request - now))
            continue
        sent = time.monotonic()
        lag = sent - next_request
        if lag > MAX_START_LAG_SECONDS:
            raise ValueError(f"Cost request cadence missed at index {len(latencies)} by {round(lag * 1000)} ms")
        invoke(len(latencies))
        duration = round((time.monotonic() - sent) * 1000, 3)
        latencies.append(duration)
        row = dict(index=len(latencies) - 1, scheduledElapsedSeconds=round(next_request - started, 3),
            startedElapsedSeconds=round(sent - started, 3), startLagMs=round(max(0, lag) * 1000, 3), durationMs=duration)
        request_rows.append(row)
        if on_request is not None:
            on_request(row)
        next_request += REQUEST_INTERVAL_SECONDS
    sample()
    time.sleep(2)
    sample()

    if len(latencies) != seconds * 2 or len(samples) < 5:
        raise ValueError("Cost workload did not complete the exact scheduled requests or memory samples")
    if any(b["elapsedSeconds"] - a["elapsedSeconds"] > INTERVAL_SECONDS + 5
           for a, b in zip(samples, samples[1:])):
        raise ValueError("Cost samples have a coverage gap")
    if samples[-2]["elapsedSeconds"] < seconds or len({row["pid"] for row in samples}) != 1:
        raise ValueError("Cost workload stopped early or changed JVM")
    warmup = seconds / 5
    steady = [row for row in samples if warmup <= row["elapsedSeconds"] <= seconds + 1]
    if len(steady) < 3 or samples[0]["elapsedSeconds"] > 5:
        raise ValueError("Cost baseline lacks warmup or running samples")
    if any(row["rssBytes"] <= 0 or row["privateBytes"] <= 0 for row in samples):
        raise ValueError("Cost samples contain invalid process memory")
    sorted_latency = sorted(latencies)
    baseline = steady[0]
    summary = dict(durationSeconds=seconds, completedRequests=len(latencies),
        observedRps=round(len(latencies) / seconds, 3), warmupSeconds=warmup,
        requestP50Ms=sorted_latency[math.ceil(len(sorted_latency) * .5) - 1],
        requestP95Ms=sorted_latency[math.ceil(len(sorted_latency) * .95) - 1],
        requestMaxMs=sorted_latency[-1], sampleCount=len(samples),
        maxStartLagMs=max(row["startLagMs"] for row in request_rows),
        maxInterStartGapMs=max(b["startedElapsedSeconds"] - a["startedElapsedSeconds"]
            for a, b in zip(request_rows, request_rows[1:])) * 1000,
        baselineRssBytes=baseline["rssBytes"], peakRssBytes=max(row["rssBytes"] for row in steady),
        rssPeakGrowthBytes=max(row["rssBytes"] for row in steady) - baseline["rssBytes"],
        finalRssBytes=samples[-1]["rssBytes"],
        baselinePrivateBytes=baseline["privateBytes"],
        privatePeakGrowthBytes=max(row["privateBytes"] for row in steady) - baseline["privateBytes"])
    return summary, samples


def write_samples(path, samples):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=("pid", "elapsedSeconds", "rssBytes", "privateBytes"))
        writer.writeheader()
        writer.writerows(samples)


def request_writer(stream):
    writer = csv.DictWriter(stream, fieldnames=REQUEST_COLUMNS)
    writer.writeheader()
    def append(row):
        writer.writerow(row)
        stream.flush()
    return append
