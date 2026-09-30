#!/usr/bin/env python3
"""Exercise real JDBC pool and SQL-stage failures through the registered Agent tools."""
import argparse
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from live_smoke import request, evidence, valid_citations


def investigate(agent, config):
    status, run = request(agent, "/api/runs", {"question": "数据库请求为什么变慢了？请结合连接池证据给出检查建议。",
        "service": "account-service", "windowMinutes": 5, "expectedSelection": config["selectionToken"]})
    assert status == 202
    deadline = time.monotonic() + 25
    while run["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() > deadline:
            raise TimeoutError("Database investigation did not finish")
        time.sleep(0.1)
        status, run = request(agent, "/api/runs/" + run["id"])
        assert status == 200
    return run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-url", default="http://127.0.0.1:18093")
    parser.add_argument("--database-url", default="http://127.0.0.1:18096")
    parser.add_argument("--mock-provider", action="store_true", help="Allow MODEL mode for a configured local protocol stub")
    args = parser.parse_args()
    agent, database = args.agent_url.rstrip("/"), args.database_url.rstrip("/")
    status, config = request(agent, "/api/config?service=account-service")
    assert status == 200 and config["protocol"] == "DATABASE_V2" and config["observationAvailable"]
    if config["mode"] != "DEMO" and not args.mock_provider:
        parser.error("Use DEMO mode, or --mock-provider with a local protocol stub")
    for body in ({"question": "数据库超时", "service": "unknown-db", "windowMinutes": 5},
                 {"question": "数据库超时", "service": "account-service", "windowMinutes": 60}):
        assert request(agent, "/api/runs", body)[0] == 400
    headers = {"X-Triage-Lab": "1"}
    assert request(database, "/lab/reset", {})[0] == 403
    assert request(database, "/lab/reset", {}, headers)[0] == 200
    empty = investigate(agent, config)
    assert empty["status"] == "INSUFFICIENT_EVIDENCE"
    rows, runs = [], [empty]
    for name, scenario, expected in [("normal", "NORMAL", "SUCCEEDED"),
        ("exhausted", "DB_POOL_EXHAUSTED", "SUCCEEDED"), ("recovery", "DB_POOL_RECOVERY", "SUCCEEDED"),
        ("sql_lock", "DB_QUERY_LOCK_WAIT", "SUCCEEDED")]:
        status, traffic = request(agent, "/api/live-lab/traffic", {"service": "account-service", "scenario": scenario, "count": 3}, headers)
        assert status == 200, (status, traffic)
        if name == "recovery": assert traffic["recoveryVerified"]
        run = investigate(agent, config)
        assert run["status"] == expected, (name, run["status"], run.get("failure"))
        assert run["scenario"] == "OBSERVED" and run["serviceInfo"]["id"] == "account-service"
        assert run["toolCalls"] == 3 and valid_citations(run)
        metrics = evidence(run, "read_service_metrics")["data"]
        pool = metrics["databasePool"]
        assert metrics["requestCount"] == 3 and "downstreamTimeoutRate" not in metrics
        if name == "exhausted":
            assert pool["acquisitionTimeoutCount"] == 3 and pool["queryCount"] == 0
            assert pool["peakActiveConnections"] == pool["maximumConnections"] and pool["peakPendingThreads"] > 0 and pool["exhaustedSamples"] > 0
        elif name == "sql_lock":
            assert pool["acquisitionTimeoutCount"] == 0 and pool["queryErrorCount"] == 3 and pool["queryCount"] == 3
            assert "SQL 执行阶段失败" in run["diagnosis"]["possibleCauses"][0]["text"]
            assert "锁等待导致" not in run["diagnosis"]["possibleCauses"][0]["text"]
        else:
            assert pool["acquisitionTimeoutCount"] == 0 and pool["queryErrorCount"] == 0 and pool["queryCount"] == 3
            assert 1 <= pool["peakActiveConnections"] <= pool["maximumConnections"]
        if config["mode"] == "MODEL":
            assessment = "DB_POOL_EXHAUSTION_OBSERVED" if name == "exhausted" else "DB_SQL_EXECUTION_FAILURE_OBSERVED" if name == "sql_lock" else "NO_DB_POOL_EXHAUSTION_OBSERVED"
            assert run["modelExecution"]["assessment"] == assessment and run["modelExecution"]["calls"] == 2
        rows.append({"case": name, "status": run["status"], "requestCount": metrics["requestCount"], "acquisitionTimeoutCount": pool["acquisitionTimeoutCount"],
            "queryCount": pool["queryCount"], "queryErrorCount": pool["queryErrorCount"]})
        runs.append(run)
    history = request(agent, "/api/runs?limit=50")[1]
    for run in runs:
        summary = next(row for row in history if row["id"] == run["id"])
        assert summary["serviceInfo"] == run["serviceInfo"]
    assert request(database, "/lab/reset", {}, headers)[0] == 200
    output = Path("target/db-pool-smoke") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True, exist_ok=False)
    for number, run in enumerate(runs):
        (output / (str(number) + ".json")).write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "summary.json").write_text(json.dumps({"mode": config["mode"], "cases": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Database pool integration passed: empty, normal, exhaustion, recovery and SQL-stage isolation; mode=" + config["mode"])


if __name__ == "__main__":
    main()
