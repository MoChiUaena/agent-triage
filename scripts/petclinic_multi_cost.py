"""Bounded, process-level measurements for the opt-in public V5 workload."""
import csv
import math
import time

from resource_soak import process_memory


INTERVAL_SECONDS = 15
REQUEST_INTERVAL_SECONDS = .5


def measure(seconds, pid, invoke, on_sample=None):
    if not 60 <= seconds <= 600 or pid <= 0:
        raise ValueError("Cost probe requires 60..600 seconds and an owned application PID")
    samples, latencies = [], []
    started = time.monotonic()
    next_sample = started
    next_request = started

    def sample():
        values = process_memory(pid)
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
        invoke(len(latencies))
        latencies.append(round((time.monotonic() - sent) * 1000, 3))
        next_request += REQUEST_INTERVAL_SECONDS
    sample()
    time.sleep(2)
    sample()

    if len(latencies) < int(seconds * 1.5) or len(samples) < 5:
        raise ValueError("Cost workload did not complete enough requests or memory samples")
    if any(b["elapsedSeconds"] - a["elapsedSeconds"] > INTERVAL_SECONDS + 5
           for a, b in zip(samples, samples[1:])):
        raise ValueError("Cost samples have a coverage gap")
    if samples[-2]["elapsedSeconds"] < seconds or len({row["pid"] for row in samples}) != 1:
        raise ValueError("Cost workload stopped early or changed JVM")
    warmup = seconds / 5
    steady = [row for row in samples if warmup <= row["elapsedSeconds"] <= seconds + 1]
    if len(steady) < 3 or samples[0]["elapsedSeconds"] > 5:
        raise ValueError("Cost baseline lacks warmup or running samples")
    if any(row["rssBytes"] <= 0 or row["privateBytes"] is not None and row["privateBytes"] <= 0 for row in samples):
        raise ValueError("Cost samples contain invalid process memory")
    sorted_latency = sorted(latencies)
    baseline = steady[0]
    summary = dict(durationSeconds=seconds, completedRequests=len(latencies),
        observedRps=round(len(latencies) / seconds, 3), warmupSeconds=warmup,
        requestP50Ms=sorted_latency[math.ceil(len(sorted_latency) * .5) - 1],
        requestP95Ms=sorted_latency[math.ceil(len(sorted_latency) * .95) - 1],
        requestMaxMs=sorted_latency[-1], sampleCount=len(samples),
        baselineRssBytes=baseline["rssBytes"], peakRssBytes=max(row["rssBytes"] for row in steady),
        rssPeakGrowthBytes=max(row["rssBytes"] for row in steady) - baseline["rssBytes"],
        finalRssBytes=samples[-1]["rssBytes"],
        baselinePrivateBytes=baseline["privateBytes"],
        privatePeakGrowthBytes=(max(row["privateBytes"] for row in steady) - baseline["privateBytes"]
            if baseline["privateBytes"] is not None else None))
    return summary, samples


def write_samples(path, samples):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=("pid", "elapsedSeconds", "rssBytes", "privateBytes"))
        writer.writeheader()
        writer.writerows(samples)
