"""Two numeric samples around native heap trimming of the owned Linux test JVM."""
import csv
import hashlib
import math
import re

TRIM_COLUMNS = ("phase", "pid", "elapsedSeconds", "rssBytes", "rollupRssBytes",
    "anonymousBytes", "pssBytes", "heapUsedBytes", "nativeNonHeapCommittedBytes")
BASE_FIELDS = ("pid", "elapsedSeconds", "rssBytes", "heapUsedBytes", "nativeNonHeapCommittedBytes")
DETAIL_FIELDS = {"rollupRssBytes": "rollupRssBytes", "anonymousBytes": "rollupAnonymousBytes",
    "pssBytes": "rollupPssBytes"}


def make_trim_row(phase, base, details):
    if phase not in ("before", "after"):
        raise ValueError("Invalid native trim phase")
    row = {"phase": phase, **{key: base[key] for key in BASE_FIELDS}}
    row.update({key: details[source] for key, source in DETAIL_FIELDS.items()})
    if type(row["pid"]) is not int or row["pid"] <= 0 or \
            type(row["elapsedSeconds"]) not in (int, float) or not math.isfinite(row["elapsedSeconds"]) or row["elapsedSeconds"] < 0:
        raise ValueError("Invalid native trim identity")
    for key in TRIM_COLUMNS[3:]:
        if type(row[key]) is not int or row[key] < 0:
            raise ValueError("Invalid native trim number: " + key)
    if row["rssBytes"] <= 0 or row["rollupRssBytes"] <= 0 or \
            row["anonymousBytes"] > row["rollupRssBytes"] or row["pssBytes"] > row["rollupRssBytes"]:
        raise ValueError("Inconsistent native trim resident pages")
    return row


def write_trim_file(path, rows):
    if len(rows) != 2 or [row.get("phase") for row in rows] != ["before", "after"] or \
            any(set(row) != set(TRIM_COLUMNS) for row in rows):
        raise ValueError("Native trim requires exactly two numeric samples")
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=TRIM_COLUMNS)
        writer.writeheader()
        writer.writerows(rows)


def trim_receipt(path):
    return dict(version=1, file="native-heap-trim.csv", rows=2,
        sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def verify_trim_file(path, receipt, closed, closed_details, operating_system):
    if operating_system != "linux" or closed["phase"] != "closed" or closed_details["phase"] != "closed":
        raise ValueError("Native trimming requires the original Linux closed sample")
    if not isinstance(receipt, dict) or set(receipt) != {"version", "file", "rows", "sha256"} or \
            type(receipt["version"]) is not int or receipt["version"] != 1 or \
            receipt["file"] != "native-heap-trim.csv" or type(receipt["rows"]) is not int or receipt["rows"] != 2 or \
            not isinstance(receipt["sha256"], str) or re.fullmatch("[a-f0-9]{64}", receipt["sha256"]) is None:
        raise ValueError("Invalid native trim receipt")
    if path.name != receipt["file"] or not path.is_file() or \
            hashlib.sha256(path.read_bytes()).hexdigest() != receipt["sha256"]:
        raise ValueError("Missing or modified native trim numbers")
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != list(TRIM_COLUMNS):
            raise ValueError("Unexpected native trim columns")
        raw_rows = list(reader)
    if len(raw_rows) != 2 or [row.get("phase") for row in raw_rows] != ["before", "after"]:
        raise ValueError("Native trim requires before and after samples")
    rows = []
    for raw in raw_rows:
        if set(raw) != set(TRIM_COLUMNS) or any(value is None for value in raw.values()):
            raise ValueError("Incomplete native trim sample")
        if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?(?:[eE][+]?[0-9]+)?", raw["elapsedSeconds"]) is None:
            raise ValueError("Invalid native trim time")
        base = dict(pid=int(raw["pid"]), elapsedSeconds=float(raw["elapsedSeconds"])) if re.fullmatch(r"[0-9]+", raw["pid"]) else None
        if base is None:
            raise ValueError("Invalid native trim PID")
        for key in BASE_FIELDS[2:]:
            if re.fullmatch(r"[0-9]+", raw[key]) is None:
                raise ValueError("Invalid native trim base number")
            base[key] = int(raw[key])
        details = {}
        for key, source in DETAIL_FIELDS.items():
            if re.fullmatch(r"[0-9]+", raw[key]) is None:
                raise ValueError("Invalid native trim page number")
            details[source] = int(raw[key])
        rows.append(make_trim_row(raw["phase"], base, details))
    expected = make_trim_row("before", closed, closed_details)
    if rows[0] != expected or rows[1]["pid"] != expected["pid"] or rows[1]["elapsedSeconds"] <= expected["elapsedSeconds"]:
        raise ValueError("Native trim does not follow the same closed JVM sample")
    return rows
