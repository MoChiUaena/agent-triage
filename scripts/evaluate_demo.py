#!/usr/bin/env python3
"""Run the versioned case set against an isolated DEMO instance; no model calls."""

import argparse
import hashlib
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "docs/evaluation/cases-v1.json"
TERMINAL = {"SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED"}


def request(base, path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        base + path,
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def load_cases():
    raw = DATASET.read_bytes()
    dataset = json.loads(raw)
    cases = dataset["cases"]
    ids = [case["id"] for case in cases]
    if dataset["version"] != 1 or len(cases) != 20 or len(set(ids)) != 20:
        raise ValueError("Expected version 1 with 20 unique cases")
    if {split: sum(case["split"] == split for case in cases) for split in ("dev", "holdout")} != {
        "dev": 10, "holdout": 10
    }:
        raise ValueError("Expected 10 dev and 10 holdout cases")
    for case in cases:
        if (case["split"] not in ("dev", "holdout")
                or case["scenario"] not in ("NORMAL", "DOWNSTREAM_TIMEOUT")
                or case["expectedDemoStatus"] not in ("SUCCEEDED", "INSUFFICIENT_EVIDENCE")
                or not 1 <= len(case["question"]) <= 200):
            raise ValueError(f"Invalid case: {case['id']}")
    return cases, hashlib.sha256(raw).hexdigest()


def check_run(run, case):
    evidence = run["evidence"]
    ids = {item["id"] for item in evidence}
    diagnosis = run.get("diagnosis")
    findings = (diagnosis["observations"] + diagnosis["possibleCauses"]) if diagnosis else []
    citations_valid = len(ids) == len(evidence) and all(
        finding["evidenceIds"] and set(finding["evidenceIds"]) <= ids for finding in findings
    )
    sequences = [event["sequence"] for event in run["events"]]
    events_valid = sequences == list(range(1, len(sequences) + 1))
    status_match = run["status"] == case["expectedDemoStatus"]
    shape_valid = bool(diagnosis) and (
        bool(diagnosis["possibleCauses"]) if run["status"] == "SUCCEEDED"
        else not diagnosis["possibleCauses"]
    )
    return {
        "statusMatch": status_match,
        "citationsValid": citations_valid,
        "eventsValid": events_valid,
        "diagnosisShapeValid": shape_valid,
        "passed": (status_match and citations_valid and events_valid and shape_valid
                   and run["mode"] == "DEMO" and run["synthetic"] is True),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--split", choices=("dev", "holdout", "all"), default="dev")
    parser.add_argument("--output", default="target/evaluation")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    cases, dataset_hash = load_cases()
    selected = [case for case in cases if args.split == "all" or case["split"] == args.split]
    config = request(base, "/api/config")
    if config["mode"] != "DEMO" or config["synthetic"] is not True:
        parser.error("The target must be in synthetic DEMO mode; no model calls are allowed.")

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = Path(args.output) / f"{timestamp}-{args.split}"
    output.mkdir(parents=True, exist_ok=False)
    (output / "runs").mkdir()
    results = []
    for case in selected:
        started = time.monotonic()
        run = request(base, "/api/runs", {
            "question": case["question"],
            "service": "order-service",
            "windowMinutes": 15,
            "scenario": case["scenario"],
            "expectedSelection": config["selectionToken"],
        })
        deadline = started + 20
        while run["status"] not in TERMINAL:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"Case {case['id']} did not finish: {run['id']}")
            time.sleep(0.1)
            run = request(base, "/api/runs/" + run["id"])
        checks = check_run(run, case)
        (output / "runs" / f"{case['id']}.json").write_text(
            json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        result = {
            "id": case["id"], "category": case["category"],
            "scenario": case["scenario"], "expectedStatus": case["expectedDemoStatus"],
            "actualStatus": run["status"], "runId": run["id"],
            "toolCalls": run["toolCalls"], "evidenceCount": len(run["evidence"]),
            "wallTimeMs": round((time.monotonic() - started) * 1000),
            "failureCode": (run.get("failure") or {}).get("code"), **checks,
        }
        results.append(result)
        print(f"{case['id']}: {run['status']} {'PASS' if result['passed'] else 'FAIL'}")

    report = {
        "kind": "demo-evaluation", "modelEvaluation": False, "synthetic": True,
        "datasetSha256": dataset_hash, "split": args.split,
        "ranAt": datetime.now(timezone.utc).isoformat(),
        "passed": sum(result["passed"] for result in results),
        "total": len(results), "cases": results,
    }
    (output / "summary.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"Saved {report['passed']}/{report['total']} results to {output}")
    return 0 if report["passed"] == report["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
