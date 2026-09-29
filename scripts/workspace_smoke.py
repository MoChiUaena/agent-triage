#!/usr/bin/env python3
"""Check workspace APIs with newly created fixed-rule records; never delete pre-existing history."""
import argparse
import json
import time
import uuid
import urllib.request
import urllib.error
from urllib.parse import urlencode
from live_smoke import request


def delete(agent, identity, confirmation, marker=True):
    headers = {"Content-Type": "application/json"}
    if marker:
        headers["X-Triage-History"] = "1"
    value = urllib.request.Request(agent + "/api/history/" + identity, method="DELETE", headers=headers,
        data=json.dumps({"confirmId": confirmation}).encode())
    try:
        with urllib.request.urlopen(value, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-url", default="http://127.0.0.1:18380")
    args = parser.parse_args()
    agent = args.agent_url.rstrip("/")
    status, config = request(agent, "/api/config")
    if status != 200 or config["mode"] != "DEMO":
        parser.error("Use an isolated DEMO instance; this check does not call a real model")
    marker = "工作区检查-" + str(uuid.uuid4())
    identities = set()
    for number in range(55):
        status, run = request(agent, "/api/runs", {"question": marker + " 订单查询 " + str(number),
            "service": "order-service", "windowMinutes": 5, "scenario": "NORMAL", "expectedSelection": config["selectionToken"]})
        assert status == 202
        deadline = time.monotonic() + 20
        while run["status"] in ("QUEUED", "RUNNING"):
            assert time.monotonic() < deadline
            time.sleep(.05)
            status, run = request(agent, "/api/runs/" + run["id"])
            assert status == 200
        assert run["status"] in ("SUCCEEDED", "INSUFFICIENT_EVIDENCE")
        identities.add(run["id"])
    seen = set()
    cursor = None
    while True:
        params = {"q": marker, "mode": "DEMO", "pageSize": 20}
        if cursor:
            params["cursor"] = cursor
        status, page = request(agent, "/api/history?" + urlencode(params))
        assert status == 200 and page["total"] == 55
        for entry in page["items"]:
            assert entry["id"] not in seen
            seen.add(entry["id"])
        cursor = page["nextCursor"]
        if not cursor:
            break
    assert seen == identities
    status, before = request(agent, "/api/statistics?days=7&service=order-service")
    assert status == 200
    identity = run["id"]
    assert identity in identities
    assert delete(agent, identity, identity, marker=False) == 403
    assert delete(agent, identity, str(uuid.uuid4())) == 400
    assert request(agent, "/api/runs/" + identity)[0] == 200
    assert delete(agent, identity, identity) == 204
    assert request(agent, "/api/runs/" + identity)[0] == 404
    status, after = request(agent, "/api/statistics?days=7&service=order-service")
    assert status == 200 and after["total"] == before["total"] - 1
    status, checks = request(agent, "/api/services/status")
    assert status == 200 and {item["service"]["id"] for item in checks} == {item["id"] for item in config["services"]}
    assert all(item["state"] in ("AVAILABLE", "EMPTY", "UNAVAILABLE", "SYNTHETIC") for item in checks)
    assert "baseUrl" not in json.dumps(checks) and "base-url" not in json.dumps(checks)
    print("Workspace smoke passed: 55 new records, cursor pagination, filters, confirmed deletion, statistics and service checks")


if __name__ == "__main__":
    main()
