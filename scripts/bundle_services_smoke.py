#!/usr/bin/env python3
"""Check the multi-service demo after its launcher has started it, using fixed rules only."""
import argparse
from live_smoke import request, evidence, valid_citations
from service_integration_smoke import investigate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-port", type=int, default=18180)
    args = parser.parse_args()
    agent = f"http://127.0.0.1:{args.base_port}"
    status, config = request(agent, "/api/config")
    assert status == 200 and config["mode"] == "DEMO"
    services = {item["id"] for item in config["services"]}
    assert services == {"order-service", "account-service", "catalog-service", "catalog-db-service"}
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
    print("Bundle services passed: four registrations, actual Starter HTTP/JDBC requests and cited diagnoses")


if __name__ == "__main__":
    main()
