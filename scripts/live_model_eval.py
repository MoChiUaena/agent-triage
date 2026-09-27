#!/usr/bin/env python3
"""Check MODEL mode against the local order/inventory lab and save each result."""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


CASES = (
    ("empty", "NORMAL", "INSUFFICIENT_EVIDENCE", "当前订单查询有足够证据判断健康状态吗？"),
    ("normal", "NORMAL", "SUCCEEDED", "当前订单查询是否出现库存下游超时？请给出证据。"),
    ("timeout", "DOWNSTREAM_TIMEOUT", "SUCCEEDED", "订单查询接口为什么变慢了？请结合当前请求给出证据和检查建议。"),
)


def request(base, path, body=None, headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request_headers = {"Content-Type": "application/json"}
    request_headers.update(headers or {})
    req = urllib.request.Request(base + path, data=data, headers=request_headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def run_agent(base, selection, scenario, question, timeout):
    status, run = request(base, "/api/runs", {
        "question": question,
        "service": "order-service", "windowMinutes": 15,
        "scenario": scenario, "expectedSelection": selection,
    })
    if status != 202:
        raise RuntimeError(f"Run submission failed ({status}): {run.get('detail', 'unknown error')}")
    deadline = time.monotonic() + timeout
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Model run did not finish: {run['id']}")
        time.sleep(0.25)
        status, run = request(base, "/api/runs/" + run["id"])
        if status != 200:
            raise RuntimeError(f"Run lookup failed ({status})")
    return run


def citations(run):
    evidence = {item["id"]: item["source"] for item in run["evidence"]}
    diagnosis = run.get("diagnosis")
    if diagnosis is None:
        return False, []
    findings = diagnosis["observations"] + diagnosis["possibleCauses"]
    valid = len(evidence) == len(run["evidence"]) and all(
        item["evidenceIds"] and set(item["evidenceIds"]) <= evidence.keys() for item in findings
    )
    sources = sorted({evidence[id] for item in findings for id in item["evidenceIds"] if id in evidence})
    return valid, sources


def summarize(run, name, expected, elapsed_ms):
    metrics = next((item["data"] for item in run["evidence"] if item["source"] == "read_service_metrics"), {})
    model = run.get("modelExecution") or {}
    citation_valid, cited_sources = citations(run)
    tools = [event["tool"] for event in run["events"] if event["type"] == "TOOL_STARTED"]
    no_data_gate = any(event["type"] == "EVIDENCE_GATE" for event in run["events"])
    unsafe_success = run["status"] == "SUCCEEDED" and metrics.get("requestCount") == 0
    accepted = (run["mode"] == "MODEL" and run["synthetic"] is False
                and run["status"] == expected and citation_valid and not unsafe_success)
    return {
        "case": name, "question": run["question"], "expectedStatus": expected, "status": run["status"],
        "statusMatch": run["status"] == expected, "accepted": accepted,
        "runId": run["id"], "scenario": run["scenario"],
        "toolCalls": run["toolCalls"], "tools": tools,
        "evidenceCount": len(run["evidence"]), "citationsValid": citation_valid,
        "citedSources": cited_sources, "unsafeSuccess": unsafe_success,
        "applicationNoDataGate": no_data_gate,
        "requestCount": metrics.get("requestCount"),
        "downstreamTimeoutRate": metrics.get("downstreamTimeoutRate"),
        "configuredModel": model.get("configuredModel"),
        "responseModel": model.get("responseModel"), "modelCalls": model.get("calls"),
        "usage": model.get("usage"),
        "failureCode": (run.get("failure") or {}).get("code"),
        "wallTimeMs": elapsed_ms,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-model-calls", action="store_true", help="Confirm model requests and possible provider fees")
    parser.add_argument("--mock-provider", action="store_true", help="Label an offline protocol stub; never claim model quality")
    parser.add_argument("--agent-url", default="http://127.0.0.1:18080")
    parser.add_argument("--sample-url", default="http://127.0.0.1:18082")
    parser.add_argument("--inventory-url", default="http://127.0.0.1:18084")
    parser.add_argument("--case", choices=[case[0] for case in CASES], help="Run one case instead of all three")
    parser.add_argument("--count", type=int, default=5, help="Requests per non-empty case (1–10)")
    parser.add_argument("--poll-timeout", type=int, default=130)
    parser.add_argument("--output", default="target/live-model-eval")
    args = parser.parse_args()
    if not args.allow_model_calls:
        parser.error("Pass --allow-model-calls before contacting the configured model.")
    if not 1 <= args.count <= 10 or args.poll_timeout < 10:
        parser.error("--count must be 1–10 and --poll-timeout must be at least 10 seconds.")
    agent, sample, inventory = (value.rstrip("/") for value in
                                (args.agent_url, args.sample_url, args.inventory_url))
    status, config = request(agent, "/api/config")
    if (status != 200 or config.get("mode") != "MODEL" or config.get("observationSource") != "LIVE"
            or config.get("synthetic") is not False or not config.get("observationAvailable")):
        parser.error("The agent must use a configured MODEL with an available LIVE observation source.")
    if args.mock_provider and config.get("model") != "triage-stub":
        parser.error("--mock-provider requires the local triage-stub model name.")
    if request(inventory, "/lab/scenario")[0] != 200:
        parser.error("Inventory service is unavailable.")
    assert request(sample, "/lab/reset", {})[0] == 200
    if request(sample, "/api/orders/warmup")[0] != 200:
        parser.error("A normal warmup order request failed.")

    selected = [case for case in CASES if args.case is None or case[0] == args.case]
    output = Path(args.output) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    (output / "runs").mkdir()
    results = []
    for name, scenario, expected, question in selected:
        current_status, current_config = request(agent, "/api/config")
        if current_status != 200 or current_config.get("selectionToken") != config["selectionToken"]:
            raise RuntimeError("Model selection changed during evaluation; stopping before another model call.")
        if name == "empty":
            if request(sample, "/lab/reset", {})[0] != 200:
                raise RuntimeError("Could not reset sample observations")
        else:
            generated_status, generated = request(agent, "/api/live-lab/traffic",
                {"scenario": scenario, "count": args.count}, {"X-Triage-Lab": "1"})
            if generated_status != 200 or generated.get("requestCount") != args.count:
                raise RuntimeError(f"Sample traffic failed ({generated_status})")
        started = time.monotonic()
        run = run_agent(agent, config["selectionToken"], scenario, question, args.poll_timeout)
        (output / "runs" / f"{name}.json").write_text(
            json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        result = summarize(run, name, expected, round((time.monotonic() - started) * 1000))
        if args.mock_provider and name == "empty" and (
                not result["applicationNoDataGate"] or result["modelCalls"] != 1):
            result["accepted"] = False
        results.append(result)
        print(f"{name}: {run['status']} | {run['toolCalls']} tools | {result['modelCalls']} model calls"
              f" | {'accepted' if result['accepted'] else 'needs review'}")

    report = {
        "kind": "live-model-protocol-check" if args.mock_provider else "live-model-run",
        "mockProvider": args.mock_provider, "modelQualityEvaluated": False,
        "syntheticObservations": False, "ranAt": datetime.now(timezone.utc).isoformat(),
        "configuredModel": config.get("model"), "provider": config.get("provider"),
        "accepted": sum(item["accepted"] for item in results), "total": len(results),
        "cases": results,
    }
    (output / "summary.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Saved results to " + str(output))
    return 0 if report["accepted"] == report["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
