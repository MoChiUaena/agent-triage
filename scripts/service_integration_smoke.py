#!/usr/bin/env python3
"""Check two registered services using actual HTTP requests and separate downstream fixtures."""
import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote
from live_smoke import request, evidence, valid_citations


def snapshot(base, path):
    end = quote(datetime.now(timezone.utc).isoformat(), safe="")
    status, value = request(base, path + "?windowMinutes=15&endTime=" + end)
    assert status == 200
    return value


def investigate(agent, service):
    status, config = request(agent, "/api/config?service=" + service)
    assert status == 200 and config["observationAvailable"]
    status, run = request(agent, "/api/runs", {
        "question": service + " 的请求为什么变慢？", "service": service, "windowMinutes": 15,
        "scenario": "NORMAL", "expectedSelection": config["selectionToken"],
    })
    assert status == 202
    deadline = time.monotonic() + 20
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() > deadline:
            raise TimeoutError("Service investigation did not finish")
        time.sleep(0.1)
        status, run = request(agent, "/api/runs/" + run["id"])
        assert status == 200
    assert run["serviceInfo"]["id"] == service
    assert all(item["data"]["service"] == service for item in run["evidence"])
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-url", default="http://127.0.0.1:18093")
    parser.add_argument("--sample-url", default="http://127.0.0.1:18082")
    parser.add_argument("--checkout-url", default="http://127.0.0.1:18092")
    parser.add_argument("--checkout-inventory-url", default="http://127.0.0.1:18094")
    parser.add_argument("--mock-provider", action="store_true", help="Allow MODEL mode only for an explicitly configured local protocol stub")
    args = parser.parse_args()
    agent, sample, checkout, downstream = [value.rstrip("/") for value in (
        args.agent_url, args.sample_url, args.checkout_url, args.checkout_inventory_url)]
    status, config = request(agent, "/api/config?service=checkout-service")
    assert status == 200 and config["protocol"] == "OBSERVATIONS_V1" and not config["labEnabled"]
    if config["mode"] != "DEMO" and not args.mock_provider:
        parser.error("Use DEMO mode, or --mock-provider with a local protocol stub")
    assert {"order-service", "checkout-service"} <= {item["id"] for item in config["services"]}
    assert "baseUrl" not in json.dumps(config) and "base-url" not in json.dumps(config)
    assert request(checkout, "/lab/scenario")[0] == 404
    for body in (
        {"question": "超时", "service": "unregistered", "windowMinutes": 15},
        {"question": "超时", "service": "checkout-service", "windowMinutes": 60},
    ):
        assert request(agent, "/api/runs", body)[0] == 400
    assert request(agent, "/api/live-lab/traffic", {
        "service": "checkout-service", "scenario": "NORMAL", "count": 5,
    }, {"X-Triage-Lab": "1"})[0] == 403

    results = []
    initial = snapshot(checkout, "/triage/observations")
    if initial["requestCount"] == 0:
        empty = investigate(agent, "checkout-service")
        assert empty["status"] == "INSUFFICIENT_EVIDENCE"
        results.append(empty)
    assert request(downstream, "/lab/scenario", {"scenario": "NORMAL"})[0] == 200
    order_before = snapshot(sample, "/lab/observations")["requestCount"]
    for number in range(3):
        assert request(checkout, "/api/requests/normal-" + str(number))[0] == 200
    normal = investigate(agent, "checkout-service")
    assert normal["status"] == "SUCCEEDED" and valid_citations(normal)
    assert normal["scenario"] == "OBSERVED"
    normal_count = evidence(normal, "read_service_metrics")["data"]["requestCount"]
    assert normal_count == initial["requestCount"] + 3
    assert snapshot(sample, "/lab/observations")["requestCount"] == order_before
    results.append(normal)

    assert request(agent, "/api/live-lab/traffic", {
        "service": "order-service", "scenario": "NORMAL", "count": 5,
    }, {"X-Triage-Lab": "1"})[0] == 200
    order = investigate(agent, "order-service")
    assert order["status"] == "SUCCEEDED" and valid_citations(order)
    assert evidence(order, "read_service_metrics")["data"]["requestCount"] == 5
    assert snapshot(checkout, "/triage/observations")["requestCount"] == normal_count
    results.append(order)

    assert request(downstream, "/lab/scenario", {"scenario": "DOWNSTREAM_TIMEOUT"})[0] == 200
    for number in range(2):
        assert request(checkout, "/api/requests/timeout-" + str(number))[0] == 504
    timeout = investigate(agent, "checkout-service")
    assert timeout["status"] == "SUCCEEDED" and valid_citations(timeout)
    metrics = evidence(timeout, "read_service_metrics")["data"]
    assert metrics["requestCount"] == normal_count + 2 and metrics["timeoutCount"] >= 2
    assert snapshot(sample, "/lab/observations")["requestCount"] == 5
    assert "订单" not in json.dumps(timeout["diagnosis"], ensure_ascii=False)
    results.append(timeout)
    assert request(downstream, "/lab/scenario", {"scenario": "NORMAL"})[0] == 200
    status, history = request(agent, "/api/runs?limit=50")
    assert status == 200
    for run in results:
        summary = next(item for item in history if item["id"] == run["id"])
        assert summary["service"] == run["service"] and summary["serviceInfo"] == run["serviceInfo"]
    output = Path("target/service-integration") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    for number, run in enumerate(results):
        (output / (str(number) + ".json")).write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Service integration passed: identity, isolation, window limits, readonly controls, citations and history; mode=" + config["mode"])


if __name__ == "__main__":
    main()
