#!/usr/bin/env python3
"""Check a running MODEL instance and save each result. Calls may incur provider fees."""
import argparse
import json
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--allow-model-calls", action="store_true", help="Acknowledge external model calls")
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--output", default="target/model-smoke")
    parser.add_argument("--scenario", choices=["NORMAL", "DOWNSTREAM_TIMEOUT"], help="Run only one scenario")
    args = parser.parse_args()
    if not args.allow_model_calls:
        parser.error("Pass --allow-model-calls to run checks that call the configured model service.")
    base = args.base_url.rstrip("/")

    def request(path, body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(base + path, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)

    config = request("/api/config")
    if config.get("mode") != "MODEL":
        parser.error("The application must be running in MODEL mode.")
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = Path(args.output) / timestamp
    output.mkdir(parents=True, exist_ok=False)
    cases = []
    scenarios = [args.scenario] if args.scenario else ["DOWNSTREAM_TIMEOUT", "NORMAL"]
    for scenario in scenarios:
        run = request("/api/runs", {"question": "订单查询接口为什么变慢了？请给出判断依据和检查建议。",
            "service": "order-service", "windowMinutes": 15, "scenario": scenario})
        deadline = time.monotonic() + 130
        while run["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(0.25)
            run = request("/api/runs/" + run["id"])
        (output / (scenario.lower() + ".json")).write_text(
            json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        ids = {item["id"] for item in run["evidence"]}
        diagnosis = run.get("diagnosis")
        citations_valid = bool(diagnosis) and all(
            finding["evidenceIds"] and set(finding["evidenceIds"]) <= ids
            for finding in diagnosis["observations"] + diagnosis["possibleCauses"])
        cases.append({"scenario": scenario, "runId": run["id"], "status": run["status"],
            "toolCalls": run["toolCalls"], "modelExecution": run.get("modelExecution"),
            "citationsValid": citations_valid, "failure": run.get("failure"),
            "passed": run["mode"] == "MODEL" and run["status"] == "SUCCEEDED" and citations_valid})
    report = {"kind": "model-smoke", "synthetic": True, "configuration": config, "cases": cases}
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("Saved results to " + str(output))
    return 0 if all(case["passed"] for case in cases) else 1


if __name__ == "__main__":
    sys.exit(main())
