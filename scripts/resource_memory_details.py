"""Numeric-only memory details for the already owned resource-test JVM."""
import re
import csv
import hashlib
import math
from pathlib import Path

NMT_CATEGORIES = {
    "nativeArenaChunkCommittedBytes": "Arena Chunk",
    "nativeMetaspaceCommittedBytes": "Metaspace",
    "nativeCompilerCommittedBytes": "Compiler",
    "nativeInternalCommittedBytes": "Internal",
    "nativeSymbolCommittedBytes": "Symbol",
    "nativeTrackingCommittedBytes": "Native Memory Tracking",
    "nativeSharedClassSpaceCommittedBytes": "Shared class space",
    "nativeModuleCommittedBytes": "Module",
    "nativeSafepointCommittedBytes": "Safepoint",
    "nativeSynchronizationCommittedBytes": "Synchronization",
    "nativeServiceabilityCommittedBytes": "Serviceability",
    "nativeStringDeduplicationCommittedBytes": "String Deduplication",
    "nativeObjectMonitorsCommittedBytes": "Object Monitors",
    "nativeGCCardSetCommittedBytes": "GCCardSet",
}
ROLLUP_FIELDS = {
    "rollupRssBytes": "Rss", "rollupPssBytes": "Pss",
    "rollupPrivateCleanBytes": "Private_Clean", "rollupPrivateDirtyBytes": "Private_Dirty",
    "rollupSharedCleanBytes": "Shared_Clean", "rollupSharedDirtyBytes": "Shared_Dirty",
    "rollupAnonymousBytes": "Anonymous", "rollupSwapBytes": "Swap",
    "rollupPssAnonBytes": "Pss_Anon", "rollupPssFileBytes": "Pss_File", "rollupPssShmemBytes": "Pss_Shmem",
}
OPTIONAL_ROLLUP = {"rollupPssAnonBytes", "rollupPssFileBytes", "rollupPssShmemBytes"}
DETAIL_COLUMNS = ("phase", "pid", "elapsedSeconds", *NMT_CATEGORIES,
    "nativeDetailRemainderBytes", *ROLLUP_FIELDS)
MAX_NMT_ROUNDING_BYTES = (len(NMT_CATEGORIES) + 7) * 1024
REQUIRED_NMT_DETAILS = {"nativeMetaspaceCommittedBytes", "nativeSymbolCommittedBytes"}

def parse_nmt_details(text, pid):
    if not text.startswith(str(pid) + ":"):
        raise ValueError("Memory details belong to a different JVM")
    result = {}
    for key, label in NMT_CATEGORIES.items():
        lines = [line for line in text.splitlines() if re.match(r"\s*-\s+" + re.escape(label) + r"\s+\(", line)]
        if len(lines) > 1:
            raise ValueError("Duplicate NMT detail category: " + label)
        if not lines:
            result[key] = None
            continue
        value = re.search(r"\(reserved=(\d+)KB, committed=(\d+)KB(?:[,)]|\s)", lines[0])
        if value is None or int(value[2]) > int(value[1]):
            raise ValueError("Invalid NMT detail category: " + label)
        result[key] = int(value[2]) * 1024
    return result

def parse_linux_rollup(text):
    result = {}
    for key, label in ROLLUP_FIELDS.items():
        lines = [line for line in text.splitlines() if line.startswith(label + ":")]
        if not lines and key in OPTIONAL_ROLLUP:
            result[key] = None
            continue
        value = re.fullmatch(re.escape(label) + r":\s+(\d+) kB\s*", lines[0]) if len(lines) == 1 else None
        if value is None:
            raise ValueError("Missing or invalid Linux rollup field: " + label)
        result[key] = int(value[1]) * 1024
    validate_rollup(result)
    return result

def validate_rollup(value):
    rss = value["rollupRssBytes"]
    total = sum(value[key] for key in ("rollupPrivateCleanBytes", "rollupPrivateDirtyBytes",
        "rollupSharedCleanBytes", "rollupSharedDirtyBytes"))
    if rss <= 0 or rss != total or value["rollupAnonymousBytes"] > rss or value["rollupPssBytes"] > rss:
        raise ValueError("Inconsistent Linux resident memory details")
    groups = [value[key] for key in OPTIONAL_ROLLUP]
    if any(number is not None for number in groups):
        if any(number is None for number in groups) or abs(sum(groups) - value["rollupPssBytes"]) > 2048:
            raise ValueError("Inconsistent Linux proportional memory groups")

def make_details_sample(base, nmt, rollup):
    if set(nmt) != set(NMT_CATEGORIES) or rollup is not None and set(rollup) != set(ROLLUP_FIELDS):
        raise ValueError("Incomplete memory detail fields")
    if any(nmt[key] is None for key in REQUIRED_NMT_DETAILS):
        raise ValueError("Memory details require Metaspace and Symbol reports")
    remainder = base["nativeUncategorizedCommittedBytes"] - sum(value for value in nmt.values() if value is not None)
    if remainder < -MAX_NMT_ROUNDING_BYTES:
        raise ValueError("Detailed NMT categories exceed the remaining committed memory")
    return {key: base[key] for key in ("phase", "pid", "elapsedSeconds")} | nmt | {
        "nativeDetailRemainderBytes": remainder} | (rollup if rollup is not None else dict.fromkeys(ROLLUP_FIELDS))

def append_details_sample(path, row):
    if set(row) != set(DETAIL_COLUMNS):
        raise ValueError("Unexpected memory detail columns")
    existed = path.exists()
    with path.open("a", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=DETAIL_COLUMNS)
        if not existed: writer.writeheader()
        writer.writerow(row)

def details_receipt(path, count):
    return dict(version=1, file="memory-details.csv", samples=count, sha256=hashlib.sha256(path.read_bytes()).hexdigest())

def verify_details_file(path, receipt, base_rows, operating_system):
    if not isinstance(receipt, dict) or set(receipt) != {"version", "file", "samples", "sha256"} or \
            type(receipt["version"]) is not int or receipt["version"] != 1 or receipt["file"] != "memory-details.csv" or \
            type(receipt["samples"]) is not int or receipt["samples"] != len(base_rows) or receipt["samples"] <= 0 or \
            not isinstance(receipt["sha256"], str) or re.fullmatch(r"[a-f0-9]{64}", receipt["sha256"]) is None:
        raise ValueError("Invalid memory detail receipt")
    if operating_system not in ("linux", "windows"):
        raise ValueError("Unsupported memory detail platform")
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != receipt["sha256"]:
        raise ValueError("Missing or changed numeric memory detail file")
    rows = []
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(DETAIL_COLUMNS):
            raise ValueError("Unexpected memory detail CSV columns")
        for raw in reader:
            if set(raw) != set(DETAIL_COLUMNS) or any(value is None for value in raw.values()):
                raise ValueError("Incomplete memory detail CSV row")
            row = dict(phase=raw["phase"], elapsedSeconds=float(raw["elapsedSeconds"]))
            for key in set(DETAIL_COLUMNS) - {"phase", "elapsedSeconds"}:
                value = raw[key]
                if value == "" and key in NMT_CATEGORIES.keys() | ROLLUP_FIELDS.keys():
                    row[key] = None
                elif re.fullmatch(r"-?[0-9]+" if key == "nativeDetailRemainderBytes" else r"[0-9]+", value):
                    row[key] = int(value)
                else:
                    raise ValueError("Invalid memory detail number: " + key)
            rows.append(row)
    if len(rows) != len(base_rows):
        raise ValueError("Memory detail file does not cover every base sample")
    for row, base in zip(rows, base_rows):
        if not math.isfinite(row["elapsedSeconds"]) or any(row[key] != base[key] for key in ("phase", "pid", "elapsedSeconds")):
            raise ValueError("Memory detail sample identity does not match base sample")
        if any(row[key] is None for key in REQUIRED_NMT_DETAILS) or "nativeUncategorizedCommittedBytes" not in base:
            raise ValueError("Missing base NMT or required detailed category")
        expected = base["nativeUncategorizedCommittedBytes"] - sum(row[key] for key in NMT_CATEGORIES if row[key] is not None)
        if row["nativeDetailRemainderBytes"] != expected or expected < -MAX_NMT_ROUNDING_BYTES:
            raise ValueError("Memory detail remainder does not match NMT base")
        if operating_system == "linux":
            if any(row[key] is None for key in ROLLUP_FIELDS.keys() - OPTIONAL_ROLLUP):
                raise ValueError("Missing Linux resident memory details")
            validate_rollup(row)
        elif any(row[key] is not None for key in ROLLUP_FIELDS):
            raise ValueError("Linux rollup values were not collected on Windows")
    return rows

def process_details(pid, operating_system):
    if type(pid) is not int or pid <= 0:
        raise ValueError("Invalid owned JVM PID")
    if operating_system == "windows": return None
    if operating_system != "linux": raise ValueError("Unsupported memory detail platform")
    return parse_linux_rollup(Path(f"/proc/{pid}/smaps_rollup").read_text(encoding="ascii"))

def detail_growth(rows, warmup, key):
    measured = [row for row in rows if row["phase"] == "closed" or row["elapsedSeconds"] >= warmup]
    values = [row[key] for row in measured]
    if not values or any(value is None for value in values): return None
    return max(values) - values[0]
