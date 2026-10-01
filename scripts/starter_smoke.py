#!/usr/bin/env python3
"""Exercise the starter in isolated JVMs; no model credentials or existing services are used."""
import argparse
import json
import os
import socket
import subprocess
import time
import threading
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote
from package_release import ROOT, project
from live_smoke import request, evidence, valid_citations
from service_integration_smoke import investigate


def observation(base):
    end = quote(datetime.now(timezone.utc).isoformat(), safe="")
    status, value = request(base, "/triage/observations?windowMinutes=5&endTime=" + end)
    assert status == 200, (status, value)
    return value


def capacity_check(agent, catalog, database, model_agent=None):
    def burst(base, path, count):
        with ThreadPoolExecutor(max_workers=8) as workers:
            results = list(workers.map(lambda _: request(base, path)[0], range(count)))
        assert results == [200] * count, results

    def wait_count(base, expected):
        deadline = time.monotonic() + 5
        while True:
            value = observation(base)
            if value["requestCount"] == expected:
                return value
            assert value["requestCount"] < expected, value["requestCount"]
            assert time.monotonic() < deadline, "Request completion was not recorded"
            time.sleep(.02)

    burst(catalog, "/api/products/demo", 26)  # Six earlier requests + 26 = the configured capacity.
    http = wait_count(catalog, 32)
    assert http["timeoutCount"] == 2
    end = quote(datetime.now(timezone.utc).isoformat(), safe="")
    path = "/triage/endpoint-observations?windowMinutes=5&endTime=" + end
    status, endpoints = request(catalog, path)
    assert status == 200 and endpoints["requestCount"] == 32
    counts = endpoints["responseStatuses"]
    assert counts["successful"] == 30 and counts["serverError"] == 2
    assert sum(counts.values()) == 32
    selected = endpoints["endpoints"][0]["endpoint"]["id"]
    assert request(catalog, path + "&endpointId=" + selected)[0] == 200
    burst(database, "/api/prices/demo", 31)  # One earlier JDBC request + 31.
    db = wait_count(database, 32)
    assert db["databasePool"]["queryCount"] == 32
    burst(catalog, "/api/products/demo", 1)
    burst(database, "/api/prices/demo", 1)
    for base in (catalog, database):
        deadline = time.monotonic() + 5
        while True:
            end = quote(datetime.now(timezone.utc).isoformat(), safe="")
            status, _ = request(base, "/triage/observations?windowMinutes=5&endTime=" + end)
            if status == 422:
                break
            assert status == 200 and time.monotonic() < deadline, status
            time.sleep(.02)
    assert request(catalog, path)[0] == 422
    assert request(catalog, path + "&endpointId=" + selected)[0] == 422
    for triage in (agent, model_agent):
        if triage is None:
            continue
        for service in ("catalog-service", "catalog-db-service"):
            status, config = request(triage, "/api/config?service=" + service)
            assert status == 200 and not config["observationAvailable"]
            assert config["observationErrorCode"] == "OBSERVATION_WINDOW_LOST"
            status, run = request(triage, "/api/runs", {
                "question": service + " 的请求为什么变慢？", "service": service, "windowMinutes": 5,
                "expectedSelection": config["selectionToken"],
            })
            assert status == 202, (status, run)
            deadline = time.monotonic() + 15
            while run["status"] in ("QUEUED", "RUNNING"):
                assert time.monotonic() < deadline, "Lost-window run did not finish"
                time.sleep(.05)
                status, run = request(triage, "/api/runs/" + run["id"])
                assert status == 200
            assert run["status"] == "FAILED" and run["failure"]["code"] == "OBSERVATION_WINDOW_LOST", run
            assert run["diagnosis"] is None
            types = [event["type"] for event in run["events"]]
            assert "TOOL_FAILED" in types and "RUN_FAILED" in types and "RUN_COMPLETED" not in types
            assert not any(item["source"] in ("read_service_metrics", "query_error_logs") for item in run["evidence"])
            if triage == model_agent:
                assert run["mode"] == "MODEL" and run["modelExecution"]["calls"] == 1
    print("Capacity checks passed: eight concurrent clients, exact HTTP/JDBC/V3 counts, overflow 422, DEMO/MODEL runs fail without a conclusion")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-port", type=int, default=18280)
    parser.add_argument("--capacity-check", action="store_true", help="Exercise small buffers and a local model protocol server")
    args = parser.parse_args()
    if not 1024 <= args.base_port <= 65526:
        parser.error("base-port must be 1024..65526")
    ports = [args.base_port + offset for offset in (0, 4, 8, 9)]
    for port in ports + ([args.base_port + 1, args.base_port + 2] if args.capacity_check else []):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    agent, inventory, catalog, database = [f"http://127.0.0.1:{port}" for port in ports]
    output = ROOT / "target/starter-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    configuration = output / "services.yml"
    configuration.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: catalog-service
      name: 商品服务
      downstream-id: inventory-service
      downstream-name: 库存服务
      base-url: {catalog}
      protocol: OBSERVATIONS_V1
      max-window-minutes: 15
    - id: catalog-db-service
      name: 商品价格服务
      downstream-id: catalog-db
      downstream-name: 价格数据库
      base-url: {database}
      protocol: DATABASE_V2
      max-window-minutes: 15
""", encoding="utf-8")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    processes, logs = [], []
    model_server = model_thread = None
    model_agent = None

    def start(directory, name, arguments):
        artifact, version = project(directory)
        jar = directory / "target" / f"{artifact}-{version}.jar"
        assert jar.is_file(), f"Build {artifact} first"
        log = (output / (name + ".log")).open("wb")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar), *arguments], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        processes.append(process)

    try:
        start(ROOT / "inventory-service", "inventory", [f"--server.port={ports[1]}"])
        capacity = ["--triage.sdk.capacity=32"] if args.capacity_check else []
        endpoints = ["--triage.sdk.endpoint-observations=true", "--triage.sdk.response-status-counts=true"] if args.capacity_check else []
        start(ROOT / "catalog-service", "catalog", [f"--server.port={ports[2]}", f"--triage.sdk.downstream-base-url={inventory}", *capacity, *endpoints])
        start(ROOT / "catalog-service", "database", [f"--server.port={ports[3]}", "--spring.profiles.active=database", *capacity])
        start(ROOT, "agent", [f"--server.port={ports[0]}", "--triage.mode=DEMO",
            "--spring.datasource.url=jdbc:h2:mem:starter-smoke;DB_CLOSE_DELAY=-1", f"--triage.settings.key-file={output / 'local.key'}",
            f"--spring.config.additional-location={configuration.as_uri()}"])
        if args.capacity_check:
            from model_protocol_stub import Handler
            model_server = ThreadingHTTPServer(("127.0.0.1", args.base_port + 2), Handler)
            model_thread = threading.Thread(target=model_server.serve_forever, daemon=True)
            model_thread.start()
            model_agent = f"http://127.0.0.1:{args.base_port + 1}"
            start(ROOT, "model-agent", [f"--server.port={args.base_port + 1}", "--triage.mode=MODEL",
                "--spring.datasource.url=jdbc:h2:mem:capacity-model;DB_CLOSE_DELAY=-1", f"--triage.settings.key-file={output / 'model-local.key'}",
                f"--spring.config.additional-location={configuration.as_uri()}", f"--triage.model.base-url=http://127.0.0.1:{args.base_port + 2}",
                "--triage.model.api-key=test-only-local", "--triage.model.name=capacity-stub", "--triage.model.timeout=5s"])
        deadline = time.monotonic() + 60
        while True:
            assert all(process.poll() is None for process in processes), "A fixture stopped; inspect target/starter-smoke logs"
            try:
                if request(inventory, "/lab/scenario")[0] == 200 and observation(catalog) and observation(database):
                    status, config = request(agent, "/api/config")
                    model_ready = model_agent is None or request(model_agent, "/api/config")[0] == 200
                    if status == 200 and config["observationAvailable"] and model_ready:
                        break
            except (OSError, AssertionError):
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError("Starter fixture startup timed out; inspect target/starter-smoke logs")
            time.sleep(.5)
        assert config["mode"] == "DEMO"
        assert not config["labEnabled"]
        empty = investigate(agent, "catalog-service")
        assert empty["status"] == "INSUFFICIENT_EVIDENCE"
        for _ in range(3):
            assert request(catalog, "/api/products/demo")[0] == 200
        normal = investigate(agent, "catalog-service")
        assert normal["status"] == "SUCCEEDED" and valid_citations(normal)
        assert evidence(normal, "read_service_metrics")["data"]["requestCount"] == 3
        assert request(inventory, "/lab/scenario", {"scenario": "DOWNSTREAM_TIMEOUT"})[0] == 200
        for _ in range(2):
            assert request(catalog, "/api/products/demo")[0] == 504
        failed = investigate(agent, "catalog-service")
        assert failed["status"] == "SUCCEEDED" and valid_citations(failed)
        metrics = evidence(failed, "read_service_metrics")["data"]
        assert metrics["requestCount"] == 5 and metrics["timeoutCount"] == 2
        assert observation(catalog)["errors"] and "示例商品" not in json.dumps(observation(catalog))
        assert request(inventory, "/lab/scenario", {"scenario": "NORMAL"})[0] == 200
        assert request(catalog, "/api/products/demo")[0] == 200
        recovered = observation(catalog)
        deadline = time.monotonic() + 3
        while recovered["requestCount"] < 6 and time.monotonic() < deadline:
            time.sleep(.05)
            recovered = observation(catalog)
        assert recovered["requestCount"] == 6 and recovered["timeoutCount"] == 2, (recovered["requestCount"], recovered["timeoutCount"])
        assert recovered["baselineRequestP95Ms"] is None
        assert request(database, "/api/prices/demo")[0] == 200
        db = investigate(agent, "catalog-db-service")
        assert db["status"] == "SUCCEEDED" and valid_citations(db)
        assert observation(database)["databasePool"]["queryCount"] == 1
        assert request(catalog, "/lab/scenario")[0] == 404
        assert request(database, "/lab/scenario")[0] == 404
        assert request(agent, "/api/live-lab/traffic", {"service": "catalog-service", "scenario": "NORMAL", "count": 1},
                       {"X-Triage-Lab": "1"})[0] == 403
        for name, value in [("empty", empty), ("normal", normal), ("timeout", failed), ("database", db)]:
            (output / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        if args.capacity_check:
            capacity_check(agent, catalog, database, model_agent)
        print("Starter smoke passed: independent JVMs, V1/V2, no-requests, timeout, request recovery, citations and readonly controls")
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for log in logs:
            log.close()
        if model_server is not None:
            model_server.shutdown()
            model_server.server_close()
            model_thread.join(timeout=5)


if __name__ == "__main__":
    main()
