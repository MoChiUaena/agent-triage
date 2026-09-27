#!/usr/bin/env python3
"""Real HTTP smoke checks for demo mode; no third-party packages or model calls."""
import argparse
import json
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18080")
    parser.add_argument("--output", default="target/smoke")
    args = parser.parse_args()
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    base = args.base_url.rstrip("/")

    def request(path, body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(base + path, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)

    cases = [
        ("timeout", "订单查询接口为什么变慢了？", "DOWNSTREAM_TIMEOUT", "SUCCEEDED"),
        ("normal", "订单查询接口为什么变慢了？", "NORMAL", "SUCCEEDED"),
        ("unrelated", "写一首诗", "NORMAL", "INSUFFICIENT_EVIDENCE"),
        ("missing-rule", "查看健康状态", "DOWNSTREAM_TIMEOUT", "INSUFFICIENT_EVIDENCE"),
    ]
    summary = []
    for name, question, scenario, expected in cases:
        started = time.monotonic()
        run = request("/api/runs", {"question": question, "service": "order-service", "windowMinutes": 15, "scenario": scenario})
        deadline = time.monotonic() + 15
        while run["status"] in ("QUEUED", "RUNNING"):
            if time.monotonic() > deadline:
                raise AssertionError(f"Execution did not finish: {run['id']}")
            time.sleep(0.05)
            run = request("/api/runs/" + run["id"])
        assert run["status"] == expected, (name, run["status"], run.get("failure"))
        assert run["mode"] == "DEMO" and run["synthetic"] is True
        ids = {evidence["id"] for evidence in run["evidence"]}
        assert len(ids) == len(run["evidence"])
        for finding in run["diagnosis"]["observations"] + run["diagnosis"]["possibleCauses"]:
            assert finding["evidenceIds"] and set(finding["evidenceIds"]) <= ids
        assert [event["sequence"] for event in run["events"]] == list(range(1, len(run["events"]) + 1))
        with urllib.request.urlopen(base + "/api/runs/" + run["id"] + "/events", timeout=10) as response:
            replay = response.read().decode("utf-8")
        assert "event:complete" in replay and "event:progress" in replay
        assert run["id"] in {item["id"] for item in request("/api/runs?limit=50")}
        (output / f"{name}.json").write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        summary.append({"case": name, "status": run["status"], "runId": run["id"], "toolCalls": run["toolCalls"],
                        "evidenceCount": len(ids), "wallTimeMs": round((time.monotonic() - started) * 1000)})
    report = {"mode": "DEMO", "synthetic": True, "modelEvaluation": False, "cases": summary}
    (output / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
