#!/usr/bin/env python3
"""Compare document-only answers with Agent tool use on the same local requests."""

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "docs/evaluation/cases-v1.json"
DEFAULT_DEV = ("D01", "D04", "D07", "D09")


def request(base, path, body=None, headers=None, timeout=90):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request_headers = {"Content-Type": "application/json"}
    request_headers.update(headers or {})
    req = urllib.request.Request(base + path, data=data, headers=request_headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def agent_run(base, case, selection, deadline_seconds):
    started = time.monotonic()
    status, run = request(base, "/api/runs", {
        "question": case["question"], "service": "order-service",
        "windowMinutes": 15, "scenario": case["scenario"],
        "expectedSelection": selection,
    })
    if status != 202:
        raise RuntimeError(f"Agent submission failed ({status}): {run.get('detail', 'unknown error')}")
    deadline = started + deadline_seconds
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Agent run did not finish: {run['id']}")
        time.sleep(0.25)
        status, run = request(base, "/api/runs/" + run["id"], timeout=15)
        if status != 200:
            raise RuntimeError(f"Agent lookup failed ({status})")
    return run, round((time.monotonic() - started) * 1000)


def citation_sources(run):
    ids = {item["id"]: item["source"] for item in run["evidence"]}
    diagnosis = run.get("diagnosis")
    if diagnosis is None:
        return False, []
    findings = diagnosis["observations"] + diagnosis["possibleCauses"]
    valid = len(ids) == len(run["evidence"]) and all(
        finding["evidenceIds"] and set(finding["evidenceIds"]) <= ids.keys()
        for finding in findings
    )
    return valid, sorted({ids[id] for finding in findings for id in finding["evidenceIds"] if id in ids})


def summarize(case, baseline, run, agent_ms, reference):
    metrics = next((item["data"] for item in run["evidence"] if item["source"] == "read_service_metrics"), {})
    valid, cited_sources = citation_sources(run)
    model = run.get("modelExecution") or {}
    baseline_ids = set(baseline["retrievedDocumentIds"])
    return {
        "case": case["id"], "category": case["category"], "question": case["question"],
        "scenario": case["scenario"], "expectedDemoStatus": case.get("expectedDemoStatus"),
        "expectedAssessment": case.get("expectedAssessment"),
        "referenceRequestCount": reference["requestCount"],
        "referenceTimeoutRate": reference["downstreamTimeoutRate"],
        "observedRequestCount": metrics.get("requestCount"),
        "observedTimeoutRate": metrics.get("downstreamTimeoutRate"),
        "documentOnly": {
            "status": baseline["status"], "answer": baseline.get("answer"),
            "uncertainty": baseline.get("uncertainty"),
            "retrievedDocumentIds": baseline["retrievedDocumentIds"],
            "citations": baseline["citations"],
            "citationsValid": set(baseline["citations"]) <= baseline_ids,
            "usage": baseline.get("usage"), "wallTimeMs": baseline["elapsedMs"],
            "failureCode": baseline.get("failureCode"),
            "applicationScopeGate": baseline.get("applicationScopeGate", False),
            "modelCalls": baseline.get("modelCalls"),
        },
        "agent": {
            "status": run["status"], "runId": run["id"], "synthetic": run["synthetic"],
            "tools": [event["tool"] for event in run["events"] if event["type"] == "TOOL_STARTED"],
            "toolCalls": run["toolCalls"], "citationsValid": valid,
            "applicationScopeGate": any(event["type"] == "SCOPE_GATE" for event in run["events"]),
            "applicationEvidenceGate": any(event["type"] == "EVIDENCE_GATE" for event in run["events"]),
            "conclusionRendered": any(event["type"] == "CONCLUSION_RENDERED" for event in run["events"]),
            "citedSources": cited_sources,
            "observations": (run.get("diagnosis") or {}).get("observations"),
            "possibleCauses": (run.get("diagnosis") or {}).get("possibleCauses"),
            "uncertainty": (run.get("diagnosis") or {}).get("uncertainty"),
            "modelCalls": model.get("calls"), "usage": model.get("usage"),
            "assessment": model.get("assessment"),
            "wallTimeMs": agent_ms, "failureCode": (run.get("failure") or {}).get("code"),
        },
        "manualReview": "pending",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-model-calls", action="store_true", help="Confirm provider calls and possible fees")
    parser.add_argument("--mock-provider", action="store_true", help="Label a local protocol stub; never claim model quality")
    parser.add_argument("--split", choices=("dev", "holdout"), default="dev")
    parser.add_argument("--allow-holdout", action="store_true", help="Explicitly run the frozen holdout set")
    parser.add_argument("--all-dev", action="store_true", help="Run every dev case in the selected dataset")
    parser.add_argument("--ids", help="Comma-separated case IDs within the chosen split, for a smaller first run")
    parser.add_argument("--agent-url", default="http://127.0.0.1:18080")
    parser.add_argument("--sample-url", default="http://127.0.0.1:18082")
    parser.add_argument("--inventory-url", default="http://127.0.0.1:18084")
    parser.add_argument("--count", type=int, default=5, help="Requests per case (1–10)")
    parser.add_argument("--poll-timeout", type=int, default=130)
    parser.add_argument("--output", default="target/live-comparison")
    parser.add_argument("--dataset", default=str(DATASET), help="Versioned case file; v1 remains historical")
    args = parser.parse_args()
    if not args.allow_model_calls:
        parser.error("Pass --allow-model-calls before contacting the configured model.")
    if args.split == "holdout" and not args.allow_holdout:
        parser.error("Pass --allow-holdout only after the dev rubric is frozen.")
    if not 1 <= args.count <= 10 or args.poll_timeout < 10:
        parser.error("--count must be 1–10 and --poll-timeout at least 10 seconds.")
    dataset_path = Path(args.dataset)
    raw = dataset_path.read_bytes()
    dataset = json.loads(raw)
    cases = dataset["cases"]
    initial_dev = dataset.get("initialDev", DEFAULT_DEV)
    requested = set(args.ids.split(",")) if args.ids else None
    selected = [case for case in cases if case["split"] == args.split and
                (case["id"] in requested if requested is not None
                 else args.split == "holdout" or args.all_dev or case["id"] in initial_dev)]
    if not selected or (requested is not None and requested != {case["id"] for case in selected}):
        parser.error("No valid cases selected for this split.")
    for case in selected:
        if case["scenario"] not in ("NORMAL", "DOWNSTREAM_TIMEOUT") or not 1 <= len(case["question"]) <= 200:
            parser.error("Invalid case request.")
        if "traffic" in case:
            total = sum(step["count"] for step in case["traffic"])
            if total > 10 or any(step["scenario"] not in ("NORMAL", "DOWNSTREAM_TIMEOUT")
                                 or not 1 <= step["count"] <= 10 for step in case["traffic"]):
                parser.error("Traffic plans must contain at most 10 local requests.")
            if case["traffic"] and case["traffic"][-1]["scenario"] != case["scenario"]:
                parser.error("The case scenario must match its final traffic step.")
    agent, sample, inventory = (value.rstrip("/") for value in
                                (args.agent_url, args.sample_url, args.inventory_url))
    status, config = request(agent, "/api/config")
    if (status != 200 or config.get("mode") != "MODEL" or config.get("observationSource") != "LIVE"
            or config.get("synthetic") is not False or not config.get("observationAvailable")):
        parser.error("Agent must use a configured MODEL with LIVE observations.")
    if args.mock_provider and config.get("model") != "triage-stub":
        parser.error("--mock-provider requires the local triage-stub model name.")
    if request(inventory, "/lab/scenario")[0] != 200:
        parser.error("Inventory service is unavailable.")
    assert request(sample, "/lab/reset", {})[0] == 200
    if request(sample, "/api/orders/warmup")[0] != 200:
        parser.error("Normal warmup request failed.")

    output = Path(args.output) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    (output / "runs").mkdir()
    results = []
    for case in selected:
        current_status, current = request(agent, "/api/config")
        if current_status != 200 or current.get("selectionToken") != config["selectionToken"]:
            raise RuntimeError("Model selection changed; stopping before another provider call.")
        expected_count = args.count
        if "traffic" in case:
            assert request(sample, "/lab/reset", {})[0] == 200
            expected_count = sum(step["count"] for step in case["traffic"])
            for step in case["traffic"]:
                assert request(sample, "/lab/scenario", {"scenario": step["scenario"]})[0] == 200
                expected_http = 200 if step["scenario"] == "NORMAL" else 504
                for index in range(step["count"]):
                    code, _ = request(sample, f"/api/orders/{case['id']}-{step['scenario']}-{index}")
                    if code != expected_http:
                        raise RuntimeError(f"Unexpected sample response for {case['id']}: {code}")
        else:
            generated_status, generated = request(agent, "/api/live-lab/traffic",
                {"scenario": case["scenario"], "count": args.count}, {"X-Triage-Lab": "1"})
            if generated_status != 200 or generated.get("requestCount") != args.count:
                raise RuntimeError(f"Sample traffic failed for {case['id']} ({generated_status})")
        baseline_status, baseline = request(agent, "/api/evaluation/document-only", {
            "question": case["question"], "scenario": case["scenario"],
            "expectedSelection": config["selectionToken"],
        }, {"X-Triage-Settings": "1"})
        if baseline_status != 200:
            raise RuntimeError(f"Document-only request failed for {case['id']} ({baseline_status})")
        run, agent_ms = agent_run(agent, case, config["selectionToken"], args.poll_timeout)
        reference_status, reference = request(sample, "/lab/observations?windowMinutes=15")
        if reference_status != 200 or reference.get("requestCount") != expected_count or reference.get("scenario") != case["scenario"]:
            raise RuntimeError(f"Lab observations changed during case {case['id']}; comparison is contaminated.")
        (output / "runs" / f"{case['id']}-document-only.json").write_text(
            json.dumps(baseline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        (output / "runs" / f"{case['id']}-agent.json").write_text(
            json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        result = summarize(case, baseline, run, agent_ms, reference)
        if run["status"] == "SUCCEEDED" and not result["agent"]["conclusionRendered"]:
            raise AssertionError("A successful model run must record application-rendered conclusions")
        if args.mock_provider and case["id"] == "D07" and (
                run["status"] != "INSUFFICIENT_EVIDENCE" or not result["agent"]["applicationScopeGate"]
                or not result["documentOnly"]["applicationScopeGate"]):
            raise AssertionError("Both unrelated-question scope gates must run")
        if args.mock_provider and case["id"] == "D10" and (
                run["status"] != "INSUFFICIENT_EVIDENCE" or not result["agent"]["applicationEvidenceGate"]
                or result["agent"]["modelCalls"] != 1):
            raise AssertionError("The missing-rule evidence gate did not run")
        if args.mock_provider and case.get("expectedAssessment"):
            actual = result["agent"]
            expected = case["expectedAssessment"]
            if expected in ("DOWNSTREAM_TIMEOUT_OBSERVED", "NO_DOWNSTREAM_TIMEOUT_OBSERVED"):
                if (actual["status"] != "SUCCEEDED" or actual["assessment"] != expected
                        or not actual["citationsValid"] or not actual["conclusionRendered"]
                        or result["observedRequestCount"] != expected_count
                        or result["observedTimeoutRate"] != reference["downstreamTimeoutRate"]):
                    raise AssertionError(f"Evidence contract failed for {case['id']}")
            elif expected == "INSUFFICIENT_EVIDENCE":
                gate = actual["applicationScopeGate"] if case["category"] == "unrelated" else actual["applicationEvidenceGate"]
                if actual["status"] != "INSUFFICIENT_EVIDENCE" or not gate or actual["conclusionRendered"]:
                    raise AssertionError(f"Application gate failed for {case['id']}")
            elif expected == "MATCHED_RULE_OR_INSUFFICIENT":
                if (actual["status"] != "INSUFFICIENT_EVIDENCE" or not actual["applicationEvidenceGate"]
                        or actual["modelCalls"] != 1):
                    raise AssertionError(f"Missing-rule gate failed for {case['id']}")
        results.append(result)
        print(f"{case['id']}: docs={baseline['status']} | agent={run['status']} |"
              f" tools={run['toolCalls']} | review pending")

    report = {
        "kind": "live-document-vs-agent-comparison", "datasetVersion": dataset["version"],
        "datasetSha256": hashlib.sha256(raw).hexdigest(),
        "split": args.split, "model": config.get("model"),
        "provider": config.get("provider", {}).get("displayName"),
        "syntheticObservations": False, "mockProvider": args.mock_provider, "modelQualityEvaluated": False,
        "ranAt": datetime.now(timezone.utc).isoformat(), "cases": results,
    }
    (output / "summary.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Saved comparison to " + str(output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
