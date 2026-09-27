#!/usr/bin/env python3
"""Exercise actual HTTP calls from the local order sample through Agent Triage."""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


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


def run_agent(base, selection):
    status, run = request(base, "/api/runs", {
        "question": "订单查询接口为什么变慢了？请给出证据和检查建议。",
        "service": "order-service", "windowMinutes": 15, "scenario": "NORMAL",
        "expectedSelection": selection,
    })
    assert status == 202, (status, run)
    deadline = time.monotonic() + 20
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() > deadline:
            raise TimeoutError(run["id"])
        time.sleep(0.1)
        status, run = request(base, "/api/runs/" + run["id"])
        assert status == 200, (status, run)
    return run


def evidence(run, source):
    return next(item for item in run["evidence"] if item["source"] == source)


def valid_citations(run):
    ids = {item["id"] for item in run["evidence"]}
    diagnosis = run.get("diagnosis")
    return bool(diagnosis) and len(ids) == len(run["evidence"]) and all(
        finding["evidenceIds"] and set(finding["evidenceIds"]) <= ids
        for finding in diagnosis["observations"] + diagnosis["possibleCauses"]
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-url", default="http://127.0.0.1:18083")
    parser.add_argument("--sample-url", default="http://127.0.0.1:18082")
    parser.add_argument("--inventory-url", default="http://127.0.0.1:18084")
    parser.add_argument("--output", default="target/live-smoke")
    args = parser.parse_args()
    agent = args.agent_url.rstrip("/")
    sample = args.sample_url.rstrip("/")
    inventory = args.inventory_url.rstrip("/")
    status, config = request(agent, "/api/config")
    if status != 200 or config.get("mode") != "DEMO" or config.get("observationSource") != "LIVE" or config.get("synthetic"):
        parser.error("The agent must be in DEMO mode with LIVE observations.")
    assert request(inventory, "/lab/scenario")[0] == 200
    assert request(sample, "/lab/reset", {})[0] == 200
    status, _ = request(sample, "/api/orders/warmup")
    assert status == 200, status
    output = Path(args.output) / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    results = []

    for name, scenario, count, expected in [
        ("empty", "NORMAL", 0, "INSUFFICIENT_EVIDENCE"),
        ("normal", "NORMAL", 5, "SUCCEEDED"),
        ("timeout", "DOWNSTREAM_TIMEOUT", 5, "SUCCEEDED"),
    ]:
        assert request(sample, "/lab/reset", {})[0] == 200
        assert request(sample, "/lab/scenario", {"scenario": scenario})[0] == 200
        assert request(inventory, "/lab/scenario")[1]["scenario"] == scenario
        responses = [request(sample, f"/api/orders/{name}-{i}") for i in range(count)]
        expected_http = 504 if scenario == "DOWNSTREAM_TIMEOUT" else 200
        assert all(code == expected_http for code, _ in responses), responses
        started = time.monotonic()
        run = run_agent(agent, config["selectionToken"])
        metrics = evidence(run, "read_service_metrics")["data"]
        logs = evidence(run, "query_error_logs")["data"]
        assert run["status"] == expected, (name, run["status"], run.get("failure"))
        assert run["mode"] == "DEMO" and run["synthetic"] is False
        assert run["scenario"] == scenario  # The server reads the actual lab scenario.
        assert metrics["synthetic"] is False and metrics["requestCount"] == count
        assert metrics["micrometerRecordedRequestCount"] >= count
        assert logs["synthetic"] is False and valid_citations(run)
        if scenario == "DOWNSTREAM_TIMEOUT":
            assert metrics["downstreamTimeoutRate"] == 1.0
            assert logs["returnedCount"] == 3
            trace_ids = {body["traceId"] for _, body in responses}
            assert {entry["traceId"] for entry in logs["entries"]} <= trace_ids
            file_logs = request(sample, "/lab/errors?windowMinutes=15")[1]
            assert {entry["traceId"] for entry in logs["entries"]} == {entry["traceId"] for entry in file_logs}
        else:
            assert metrics["downstreamTimeoutRate"] == 0
            assert logs["returnedCount"] == 0
        if count:
            meter_status, meter = request(sample, "/actuator/metrics/sample.order.requests")
            assert meter_status == 200 and meter["name"] == "sample.order.requests"
        (output / f"{name}.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        results.append({"case": name, "status": run["status"], "runId": run["id"],
                        "requestCount": metrics["requestCount"], "orderP95Ms": metrics["orderP95Ms"],
                        "downstreamP95Ms": metrics["downstreamP95Ms"],
                        "downstreamTimeoutRate": metrics["downstreamTimeoutRate"],
                        "errorCount": logs["returnedCount"], "citationsValid": True,
                        "wallTimeMs": round((time.monotonic() - started) * 1000)})
        print(f"{name}: {run['status']} ({count} actual requests)")
    denied, _ = request(agent, "/api/live-lab/traffic", {"scenario": "NORMAL", "count": 1})
    assert denied == 403, denied
    generated_status, generated = request(agent, "/api/live-lab/traffic",
        {"scenario": "DOWNSTREAM_TIMEOUT", "count": 2}, {"X-Triage-Lab": "1"})
    assert generated_status == 200 and generated["requestCount"] == 2 and generated["timeoutCount"] == 2
    inventory_meter_status, inventory_meter = request(inventory, "/actuator/metrics/sample.inventory.duration")
    assert inventory_meter_status == 200 and inventory_meter["name"] == "sample.inventory.duration"
    summary = {"kind": "live-sample-smoke", "synthetic": False, "modelEvaluation": False,
               "labControlVerified": True, "cases": results}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Saved results to " + str(output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
