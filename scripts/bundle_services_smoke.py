#!/usr/bin/env python3
"""Check the multi-service demo after its launcher has started it, using fixed rules only."""
import argparse
from pathlib import Path
from live_smoke import request, evidence, valid_citations
from service_integration_smoke import investigate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-port", type=int, default=18180)
    parser.add_argument("--bundle-directory", type=Path, help="Extracted bundle root for source checks")
    args = parser.parse_args()
    agent = f"http://127.0.0.1:{args.base_port}"
    status, config = request(agent, "/api/config")
    assert status == 200 and config["mode"] == "DEMO"
    services = {item["id"] for item in config["services"]}
    assert services == {"order-service", "account-service", "catalog-service", "catalog-db-service", "ticket-service"}
    for identity in services:
        status, selected = request(agent, "/api/config?service=" + identity)
        assert status == 200 and selected["observationAvailable"]
    assert request(f"http://127.0.0.1:{args.base_port + 2}", "/lab/scenario", {"scenario": "NORMAL"})[0] == 200
    assert request(agent, "/api/config?service=order-service")[1]["observationAvailable"]
    status, product = request(f"http://127.0.0.1:{args.base_port + 8}", "/api/products/demo")
    assert status == 200 and product["available"]
    http = investigate(agent, "catalog-service")
    assert http["status"] == "SUCCEEDED" and valid_citations(http)
    assert evidence(http, "read_service_metrics")["data"]["requestCount"] >= 1
    assert request(f"http://127.0.0.1:{args.base_port + 9}", "/api/prices/demo")[0] == 200
    database = investigate(agent, "catalog-db-service")
    assert database["status"] == "SUCCEEDED" and valid_citations(database)
    assert evidence(database, "read_service_metrics")["data"]["databasePool"]["queryCount"] >= 1
    ticket = f"http://127.0.0.1:{args.base_port + 10}"
    assert request(f"http://127.0.0.1:{args.base_port + 12}", "/health")[0] == 200
    for _ in range(2):
        assert request(ticket, "/api/tickets/summary")[0] == 200
        assert request(ticket, "/api/tickets/BUNDLE-1")[0] == 504
    status, endpoints = request(agent, "/api/services/ticket-service/endpoints?windowMinutes=5")
    assert status == 200 and len(endpoints["endpoints"]) == 2
    if args.bundle_directory:
        source_root = args.bundle_directory.resolve() / "projects/ticket-service"
        status, binding = request(agent, "/api/source-projects", {"name":"包内工单源码","service":"ticket-service","directory":str(source_root)}, {"X-Triage-Source":"1"})
        assert status == 200 and not binding["modelSharing"]
        status, check = request(agent, "/api/services/ticket-service/source-check?windowMinutes=5")
        assert status == 200 and check["state"] == "READY"
        failed = next(value["endpoint"] for value in endpoints["endpoints"] if value["endpoint"]["routeTemplate"] == "/api/tickets/{id}")
        healthy = next(value["endpoint"] for value in endpoints["endpoints"] if value["endpoint"]["routeTemplate"] == "/api/tickets/summary")
        def locate(endpoint):
            import time
            status, run = request(agent, "/api/runs", {"question":"工单请求为什么变慢？","service":"ticket-service","windowMinutes":5,
                "endpointId":endpoint["id"],"includeSource":True,"expectedSourceRevision":binding["revision"],"expectedSourceProjectId":binding["id"]}, {"X-Triage-Source":"1"})
            assert status == 202
            for _ in range(100):
                if run["status"] not in ("RUNNING", "QUEUED"): break
                time.sleep(.1); _, run = request(agent, "/api/runs/" + run["id"])
            assert run["status"] == "SUCCEEDED" and valid_citations(run)
            return run
        fault = locate(failed); normal = locate(healthy)
        assert fault["sourceAnalysis"]["graph"]["endpointMatches"][0]["version"]["state"] == "MATCHED"
        assert normal["sourceAnalysis"]["graph"]["failureMatches"] == []
        assert evidence(fault,"read_service_metrics")["data"]["timeoutCount"] == 2
        gateway = source_root / "src/main/java/example/helpdesk/AssignmentGateway.java"
        original = gateway.read_bytes()
        try:
            gateway.write_bytes(original + b'\n// bundled source verification revision\n')
            _, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, {"X-Triage-Source":"1"})
            different = locate(failed)
            assert different["sourceAnalysis"]["state"] == "SOURCE_VERSION_DIFFERENT" and different["sourceAnalysis"]["graph"]["nodes"] == []
            assert request(agent, "/api/runs/" + fault["id"])[1]["sourceAnalysis"] == fault["sourceAnalysis"]
        finally:
            gateway.write_bytes(original)
            request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, {"X-Triage-Source":"1"})
    print("Bundle services passed: five registrations, HTTP/JDBC/V3, independent assignment timeout, packaged source digests, version mismatch and frozen history")


if __name__ == "__main__":
    main()
