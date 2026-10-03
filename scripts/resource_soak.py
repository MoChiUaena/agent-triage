#!/usr/bin/env python3
"""Sample only this isolated Maven test JVM; export numeric diagnostics, never application data."""
import argparse
import csv
import ctypes
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import signal
import subprocess
import time

from http_resource_contract import HTTP_COMPONENTS, parse_http_receipt

ROOT = Path(__file__).resolve().parents[1]
MIB = 1024 * 1024


def diagnostic_pid(text, pid):
    if not text.startswith(str(pid) + ":"):
        raise ValueError("Diagnostic output belongs to a different JVM")


def parse_native_memory(text, pid, require_breakdown=False):
    diagnostic_pid(text, pid)
    total = re.search(r"Total: reserved=(\d+)KB, committed=(\d+)KB", text)
    heap = re.search(r"Java Heap \(reserved=(\d+)KB, committed=(\d+)KB\)", text)
    threads = re.search(r"\(threads? #(\d+)\)", text)
    if not all((total, heap, threads)):
        details = "; ".join(line.strip() for line in text.splitlines()
            if "Total:" in line or "Java Heap" in line or "Thread" in line or "thread" in line)
        raise ValueError("Missing NMT summary, Java heap or thread count: " + details[:1500])
    reserved, committed, heap_committed = int(total[1]) * 1024, int(total[2]) * 1024, int(heap[2]) * 1024
    if not reserved >= committed >= heap_committed > 0:
        raise ValueError("Inconsistent NMT totals")
    value = dict(nativeReservedBytes=reserved, nativeCommittedBytes=committed,
        javaHeapCommittedBytes=heap_committed, nativeNonHeapCommittedBytes=committed - heap_committed,
        nativeThreadCount=int(threads[1]))
    for category in ("Class", "Thread", "Code", "GC", "Other"):
        match = re.search(r"-\s+" + category + r" \(reserved=\d+KB, committed=(\d+)KB\)", text)
        if require_breakdown and category != "Other" and match is None:
            raise ValueError("Missing owned HTTP NMT category: " + category)
        value["native" + category + "CommittedBytes"] = int(match[1]) * 1024 if match else 0
    value["nativeUncategorizedCommittedBytes"] = value["nativeNonHeapCommittedBytes"] - sum(
        value["native" + category + "CommittedBytes"] for category in ("Class", "Thread", "Code", "GC", "Other"))
    if value["nativeUncategorizedCommittedBytes"] < 0:
        raise ValueError("Inconsistent NMT category totals")
    return value


def parse_heap(text, pid):
    diagnostic_pid(text, pid)
    value = re.search(r"heap\s+total\s+\d+[KMG]B?,\s+used\s+(\d+)([KMG])B?", text)
    if not value:
        raise ValueError("Missing G1 heap-used diagnostic")
    return int(value[1]) * {"K": 1024, "M": MIB, "G": 1024 * MIB}[value[2]]


def process_memory(pid):
    if os.name != "nt":
        status = Path(f"/proc/{pid}/status").read_text(encoding="ascii")
        rss = re.search(r"^VmRSS:\s+(\d+) kB$", status, re.MULTILINE)
        if not rss:
            raise ValueError("Missing process resident memory")
        return dict(rssBytes=int(rss[1]) * 1024, privateBytes=None)
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                "PagefileUsage", "PeakPagefileUsage", "PrivateUsage")]
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    query = kernel.K32GetProcessMemoryInfo
    query.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    query.restype = wintypes.BOOL
    handle = kernel.OpenProcess(0x1000 | 0x10, False, pid)
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        value = Counters(); value.cb = ctypes.sizeof(value)
        if not query(handle, ctypes.byref(value), value.cb):
            raise ctypes.WinError(ctypes.get_last_error())
        return dict(rssBytes=value.WorkingSetSize, privateBytes=value.PrivateUsage)
    finally:
        kernel.CloseHandle(handle)


def summarize(samples, seconds, warmup, interval):
    if not samples or {item["pid"] for item in samples} != {samples[0]["pid"]}:
        raise ValueError("Samples must belong to one workload JVM")
    elapsed = [item["elapsedSeconds"] for item in samples]
    if any(not math.isfinite(value) or value < 0 for value in elapsed) or any(a >= b for a, b in zip(elapsed, elapsed[1:])):
        raise ValueError("Invalid or nonmonotonic sample times")
    running = [item for item in samples if item["phase"] == "running"]
    closed = [item for item in samples if item["phase"] == "closed"]
    steady = [item for item in running if item["elapsedSeconds"] >= warmup]
    if len(steady) < 3 or not closed or closed[0]["elapsedSeconds"] < seconds - 1:
        raise ValueError("Missing post-warmup samples, full duration or closed-phase sample")
    if any(b["elapsedSeconds"] - a["elapsedSeconds"] > interval + 5 for a, b in zip(steady, steady[1:])):
        raise ValueError("Post-warmup sampling has a coverage gap")
    if steady[0]["elapsedSeconds"] - warmup > interval + 5 or closed[0]["elapsedSeconds"] - steady[-1]["elapsedSeconds"] > interval + 5:
        raise ValueError("Post-warmup sampling does not cover the start or closing boundary")
    baseline = steady[0]
    measured = steady + closed
    native_growth = max(item["nativeNonHeapCommittedBytes"] for item in measured) - baseline["nativeNonHeapCommittedBytes"]
    rss_growth = max(item["rssBytes"] for item in measured) - baseline["rssBytes"]
    if native_growth > 64 * MIB or rss_growth > 128 * MIB:
        raise ValueError(f"Post-warmup growth exceeds gate: native={native_growth}, resident={rss_growth}")
    return dict(status="passed", requestedSeconds=seconds, warmupSeconds=warmup, intervalSeconds=interval,
        growthIncludesClosed=True,
        runningSamples=len(running), closedSample=closed[-1], baseline=baseline,
        postWarmupPeakNativeGrowthBytes=native_growth, postWarmupPeakResidentGrowthBytes=rss_growth,
        peakNativeThreadCount=max(item["nativeThreadCount"] for item in steady),
        peakHeapUsedBytes=max(item["heapUsedBytes"] for item in steady),
        nativeGrowthGateBytes=64 * MIB, residentGrowthGateBytes=128 * MIB)


def parse_workload_result(text, component, seconds):
    receipts = re.findall(r"RESOURCE_RESULT " + component + r" ([^\r\n]+)", text)
    if len(receipts) != 1:
        raise ValueError("Missing or duplicate completed workload receipt")
    if component in HTTP_COMPONENTS:
        return parse_http_receipt(receipts[0], component, seconds)
    fields = dict(re.findall(r"([A-Za-z]+)=([0-9]+|true|false)", receipts[0]))
    shared = {"seconds", "cycles", "cancelled", "baselineHeap", "peakHeap", "finalHeap", "connections"}
    required = shared | ({"fresh", "registry", "queues"} if component == "agent" else {"requests", "jdbc", "workerContexts", "samplerStopped"})
    if set(fields) != required:
        raise ValueError("Incomplete workload receipt")
    value = {key: (raw == "true" if raw in ("true", "false") else int(raw)) for key, raw in fields.items()}
    if value["seconds"] != seconds or value["cycles"] <= 0 or value["connections"] != 0:
        raise ValueError("Wrong workload duration or borrowed connections")
    cycles = value["cycles"]
    if component == "agent":
        if value["fresh"] != cycles or value["cancelled"] != cycles * 5 or value["registry"] or value["queues"]:
            raise ValueError("Agent workload or cleanup is incomplete")
    elif value["requests"] != cycles or value["jdbc"] != cycles * 2 or value["cancelled"] != cycles or value["workerContexts"] or value["samplerStopped"] is not True:
        raise ValueError("Starter workload or cleanup is incomplete")
    if max(value["peakHeap"], value["finalHeap"]) > value["baselineHeap"] + 64 * MIB:
        raise ValueError("Retained heap exceeds the workload gate")
    return value


def stop_owned_processes(child):
    if child is not None and child.poll() is None:
        if os.name == "nt":
            # The wrapper is a command shell. Stop its still-owned tree before
            # terminating that root; otherwise its Maven/test JVMs can survive.
            subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15,
                check=True, creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            os.killpg(child.pid, signal.SIGTERM)
        try: child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--component", required=True, choices=("agent", "starter", *HTTP_COMPONENTS))
    parser.add_argument("--seconds", type=int, default=3600)
    args = parser.parse_args()
    if not 10 <= args.seconds <= 7200:
        parser.error("seconds must be 10..7200")
    interval, warmup = min(30, args.seconds / 10), min(120, args.seconds / 5)
    output = ROOT / "target/resource-soak" / (args.component + "-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    output.mkdir(parents=True)
    marker = output / "workload.state"
    windows = os.name == "nt"
    jcmd = Path(os.environ["JAVA_HOME"]) / "bin" / ("jcmd.exe" if windows else "jcmd")
    command = [str(ROOT / ("mvnw.cmd" if windows else "mvnw")), "-B", "-ntp"]
    if args.component != "agent":
        command += ["-f", str(ROOT / "triage-spring-boot-starter/pom.xml")]
    command += ["-Dtest=" + ("RunResourceLifecycleTest" if args.component == "agent" else "HttpResourceLifecycleTest" if args.component in HTTP_COMPONENTS else "ResourceLifecycleTest"),
        f"-Dtriage.resource.seconds={args.seconds}", f"-Dtriage.resource.marker={marker}",
        "-DargLine=-Xms96m -Xmx256m -XX:+UseG1GC -XX:NativeMemoryTracking=summary", "test"]
    if args.component in HTTP_COMPONENTS:
        command.insert(-1, "-Dtriage.resource.http.enabled=" + str(args.component == "http-enabled").lower())
    samples, child, pid, started = [], None, None, time.monotonic()
    try:
        with (output / "maven.log").open("wb") as log:
            child = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if windows else 0, start_new_session=not windows)
            deadline, last_sample = started + args.seconds + 240, -float("inf")
            observed = None
            while child.poll() is None:
                if time.monotonic() > deadline:
                    raise TimeoutError("Owned resource test exceeded its deadline")
                if marker.is_file():
                    fields = marker.read_text(encoding="utf-8").strip().split(",")
                    if len(fields) == 3 and fields[0] in ("running", "closed"):
                        phase, observed_pid, phase_elapsed = fields[0], int(fields[1]), float(fields[2])
                        if pid is not None and pid != observed_pid:
                            raise ValueError("Workload JVM changed")
                        pid = observed_pid
                        if observed is None:
                            observed = time.monotonic()
                        elapsed = phase_elapsed if phase == "closed" else time.monotonic() - observed
                        if (phase == "closed" and not any(item["phase"] == "closed" for item in samples)) or (phase == "running" and elapsed - last_sample >= interval):
                            def diagnostic(*arguments):
                                return subprocess.check_output([str(jcmd), str(pid), *arguments], text=True, timeout=10,
                                    creationflags=subprocess.CREATE_NO_WINDOW if windows else 0)
                            value = dict(phase=phase, pid=pid, elapsedSeconds=elapsed)
                            value.update(parse_native_memory(diagnostic("VM.native_memory", "summary", "scale=KB"), pid, require_breakdown=args.component in HTTP_COMPONENTS))
                            value["heapUsedBytes"] = parse_heap(diagnostic("GC.heap_info"), pid)
                            value.update(process_memory(pid))
                            samples.append(value); last_sample = elapsed
                            with (output / "memory.csv").open("w", newline="", encoding="utf-8") as file:
                                writer = csv.DictWriter(file, fieldnames=list(value)); writer.writeheader(); writer.writerows(samples)
                            print(f"RESOURCE_SAMPLE {args.component} phase={phase} elapsed={elapsed:.1f}s heap={value['heapUsedBytes']} native={value['nativeNonHeapCommittedBytes']} rss={value['rssBytes']}", flush=True)
                time.sleep(.2)
            if child.returncode != 0:
                print((output / "maven.log").read_text(encoding="utf-8", errors="replace")[-5000:], flush=True)
                raise RuntimeError(f"Maven workload failed with exit {child.returncode}; see {output / 'maven.log'}")
        summary = summarize(samples, args.seconds, warmup, interval)
        summary.update(component=args.component, operatingSystem="windows" if windows else "linux",
            workload=parse_workload_result((output / "maven.log").read_text(encoding="utf-8", errors="replace"), args.component, args.seconds),
            sourceCommit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            sourceTreeDirty=bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()))
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print("RESOURCE_SOAK_PASSED " + json.dumps(summary), flush=True)
    except BaseException as error:
        (output / "summary.json").write_text(json.dumps({"status": "failed", "component": args.component,
            "error": str(error), "samples": len(samples)}, indent=2) + "\n", encoding="utf-8")
        try:
            stop_owned_processes(child)
        except Exception as cleanup_error:
            print("RESOURCE_CLEANUP_FAILED " + str(cleanup_error), flush=True)
        raise


if __name__ == "__main__":
    main()
