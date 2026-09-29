#!/usr/bin/env python3
"""Run an independent helpdesk app, actual HTTP timeouts and local source references."""
import argparse
import json
import os
import shutil
import socket
import subprocess
import threading
import urllib.request
import time
from urllib.parse import urlencode
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from package_release import ROOT, project
from live_smoke import request, evidence, valid_citations


class AssignmentHandler(BaseHTTPRequestHandler):
    slow = False

    def do_GET(self):
        if not self.path.startswith("/assignments/"):
            self.send_error(404)
            return
        if self.slow:
            time.sleep(.7)
        body = b'{"assigned":true}'
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-port", type=int, help="Default: 18320 for V1, 18340 for V3")
    parser.add_argument("--serve-assignment", action="store_true", help="Keep only the local assignment fixture running")
    parser.add_argument("--slow", action="store_true")
    parser.add_argument("--protocol-v3", action="store_true", help="Verify endpoint-scoped MVC observations")
    args = parser.parse_args()
    if args.base_port is None:
        args.base_port = 18340 if args.protocol_v3 else 18320
    if args.serve_assignment:
        AssignmentHandler.slow = args.slow
        ThreadingHTTPServer(("127.0.0.1", args.base_port), AssignmentHandler).serve_forever()
        return
    if not 1024 <= args.base_port <= 65533:
        parser.error("base-port must be 1024..65533")
    ports = [args.base_port + offset for offset in range(3)]
    for port in ports:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    agent, ticket, dependency = [f"http://127.0.0.1:{port}" for port in ports]
    output = ROOT / "target/source-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    # This copied source directory belongs to this verification, not any existing sample.
    external = output / "helpdesk-project"
    shutil.copytree(ROOT / "verification/ticket-service/src", external / "src")
    (external / ".env").write_text("PRIVATE_CONFIG=fixture-local-only\n", encoding="utf-8")
    configuration = output / "services.yml"
    configuration.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: ticket-service
      name: 工单服务
      downstream-id: assignment-service
      downstream-name: 分配服务
      base-url: {ticket}
      protocol: {"OBSERVATIONS_V3" if args.protocol_v3 else "OBSERVATIONS_V1"}
      max-window-minutes: 15
""", encoding="utf-8")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    processes, logs = [], []
    server = ThreadingHTTPServer(("127.0.0.1", ports[2]), AssignmentHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    def start(directory, name, options):
        artifact, version = project(directory)
        jar = directory / "target" / f"{artifact}-{version}.jar"
        assert jar.is_file(), f"Build {artifact} first"
        log = (output / (name + ".log")).open("wb")
        logs.append(log)
        process = subprocess.Popen([java, "-jar", str(jar), *options], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        processes.append(process)

    def investigate(endpoint=None):
        body = {"question": "服务请求为什么变慢？" if args.protocol_v3 else "工单接口 /api/tickets/{id} 为什么慢？", "service": "ticket-service",
            "windowMinutes": 5, "includeSource": True, "expectedSourceRevision": binding["revision"], "expectedSourceProjectId": binding["id"]}
        if endpoint:
            body["endpointId"] = endpoint
        status, value = request(agent, "/api/runs", body, {"X-Triage-Source": "1"})
        assert status == 202, (status, value)
        deadline = time.monotonic() + 10
        while value["status"] in ("RUNNING", "QUEUED") and time.monotonic() < deadline:
            time.sleep(.1)
            status, value = request(agent, "/api/runs/" + value["id"])
        assert value["status"] == "SUCCEEDED" and valid_citations(value), value.get("failure")
        analysis = value["sourceAnalysis"]
        assert analysis["state"] == "LOCAL" and not analysis["modelUsed"]
        assert analysis["excerpts"]
        for excerpt in analysis["excerpts"]:
            lines = (external / excerpt["path"]).read_text(encoding="utf-8").splitlines()
            assert excerpt["content"] == "\n".join(lines[excerpt["startLine"] - 1:excerpt["endLine"]])
        return value

    try:
        start(ROOT / "verification/ticket-service", "ticket", [f"--server.port={ports[1]}", f"--triage.sdk.downstream-base-url={dependency}",
            f"--triage.sdk.endpoint-observations={str(args.protocol_v3).lower()}", f"--triage.sdk.exception-locations={str(args.protocol_v3).lower()}",
            "--triage.sdk.application-packages=example.helpdesk"])
        start(ROOT, "agent", [f"--server.port={ports[0]}", "--triage.mode=DEMO", "--spring.datasource.url=jdbc:h2:mem:source-smoke;DB_CLOSE_DELAY=-1",
            f"--spring.config.additional-location={configuration.as_uri()}", f"--triage.settings.key-file={output / 'local.key'}"])
        deadline = time.monotonic() + 60
        while True:
            assert all(process.poll() is None for process in processes), "Fixture stopped; inspect target/source-smoke logs"
            try:
                if request(agent, "/api/config")[0] == 200 and request(ticket, "/api/tickets/T-1")[0] == 200:
                    break
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError("Source fixture startup timed out")
            time.sleep(.5)
        status, binding = request(agent, "/api/source-projects", {"name": "外部工单项目", "service": "ticket-service", "directory": str(external)}, {"X-Triage-Source": "1"})
        assert status == 200 and binding["files"] == 6 and not binding["modelSharing"], (status, binding)
        normal = investigate()
        assert evidence(normal, "read_service_metrics")["data"]["timeoutCount"] == 0
        AssignmentHandler.slow = True
        for _ in range(2):
            assert request(ticket, "/api/tickets/T-1")[0] == 504
        failed = investigate()
        assert evidence(failed, "read_service_metrics")["data"]["timeoutCount"] == 2
        excerpts = failed["sourceAnalysis"]["excerpts"]
        graph = failed["sourceAnalysis"]["graph"]
        references = excerpts + [node["excerpt"] for node in graph["nodes"]] + [excerpt for match in graph.get("failureMatches", [])
            for frame in match["frames"] for excerpt in frame["excerpts"]]
        assert any(value["method"] == "lookup" and "retrieve" in value["content"] for value in references)
        assert graph["state"] == "READY" and not graph["truncated"]
        assert {node["excerpt"]["className"] for node in graph["nodes"]} >= {"example.helpdesk.TicketController", "example.helpdesk.DefaultTicketService", "example.helpdesk.AssignmentGateway", "example.helpdesk.TicketFormatter"}
        assert any(edge["resolution"] == "CANDIDATE" and "注入待确认" in edge["message"] for edge in graph["edges"])
        boundaries = [edge for edge in graph["edges"] if edge["kind"] == "HTTP"]
        assert len(boundaries) == 1
        assert graph["evidenceLinks"][0]["kind"] == "HTTP_TIMEOUT"
        assert graph["evidenceLinks"][0]["edgeIds"] == [boundaries[0]["id"]]
        if args.protocol_v3:
            assert graph["endpointMatches"][0]["state"] == "MATCHED"
            assert graph["endpointMatches"][0]["endpoint"]["handlerMethod"] == "ticket"
            failures = graph["failureMatches"]
            assert len(failures) == 2 and all(match["kind"] == "HTTP_CLIENT_FAILURE" for match in failures)
            expected_line = next(i + 1 for i, text in enumerate((external / "src/main/java/example/helpdesk/AssignmentGateway.java").read_text(encoding="utf-8").splitlines())
                if "return assignments.get()" in text)
            for failure in failures:
                gateway_frame = next(frame for frame in failure["frames"] if frame["frame"]["className"] == "example.helpdesk.AssignmentGateway")
                assert gateway_frame["state"] == "LINE_MATCH" and gateway_frame["frame"]["lineNumber"] == expected_line
                assert gateway_frame["excerpts"][0]["startLine"] <= expected_line <= gateway_frame["excerpts"][0]["endLine"]
            assert {match["traceId"] for match in failures} == {entry["traceId"] for entry in evidence(failed, "query_error_logs")["data"]["entries"]}
            for _ in range(2):
                assert request(ticket, "/api/tickets/summary")[0] == 200
            choices = request(agent, "/api/services/ticket-service/endpoints?windowMinutes=5")[1]["endpoints"]
            healthy_endpoint = next(item["endpoint"] for item in choices if item["endpoint"]["routeTemplate"] == "/api/tickets/summary")
            failed_endpoint = next(item["endpoint"] for item in choices if item["endpoint"]["routeTemplate"] == "/api/tickets/{id}")
            healthy = investigate(healthy_endpoint["id"])
            healthy_metrics = evidence(healthy, "read_service_metrics")["data"]
            assert healthy_metrics["requestCount"] == 2 and healthy_metrics["timeoutCount"] == 0
            assert healthy["endpoint"] == healthy_endpoint and healthy["sourceAnalysis"]["graph"]["nodes"][0]["signature"] == "ticket()"
            assert healthy["sourceAnalysis"]["graph"]["failureMatches"] == []
            endpoint_failed = investigate(failed_endpoint["id"])
            failed_metrics = evidence(endpoint_failed, "read_service_metrics")["data"]
            assert failed_metrics["requestCount"] == 3 and failed_metrics["timeoutCount"] == 2
            assert endpoint_failed["sourceAnalysis"]["graph"]["nodes"][0]["signature"] == "ticket(String)"
            assert request(agent, "/api/runs/" + healthy["id"])[1]["endpoint"] == healthy_endpoint
            assert request(agent, "/api/history/endpoints?service=ticket-service")[1] == sorted(
                [failed_endpoint, healthy_endpoint], key=lambda value: value["routeTemplate"])
            recent = request(agent, "/api/runs?limit=20")[1]
            assert next(item for item in recent if item["id"] == healthy["id"])["endpoint"] == healthy_endpoint
            params = urlencode({"service": "ticket-service", "endpointId": healthy_endpoint["id"]})
            history = request(agent, "/api/history?" + params)[1]
            assert history["total"] == 1 and history["items"][0]["id"] == healthy["id"] and history["items"][0]["endpoint"] == healthy_endpoint
            statistics = request(agent, "/api/statistics?days=7&" + params)[1]
            assert statistics["total"] == 1 and statistics["endpointId"] == healthy_endpoint["id"] and statistics["modelCalls"] == 0
            whole = request(agent, "/api/history?service=ticket-service&endpointId=SERVICE")[1]
            assert whole["total"] == 2 and all(item["endpoint"] is None for item in whole["items"])
        for node in graph["nodes"]:
            excerpt = node["excerpt"]
            lines = (external / excerpt["path"]).read_text(encoding="utf-8").splitlines()
            assert excerpt["content"] == "\n".join(lines[excerpt["startLine"] - 1:excerpt["endLine"]])
        for edge in graph["edges"]:
            excerpt = edge["callSite"]
            assert excerpt["startLine"] <= edge["line"] <= excerpt["endLine"]
            lines = (external / excerpt["path"]).read_text(encoding="utf-8").splitlines()
            assert excerpt["content"] == "\n".join(lines[excerpt["startLine"] - 1:excerpt["endLine"]])
        status, matches = request(agent, "/api/source-projects/" + binding["id"] + "/search?q=%2Fapi%2Ftickets")
        assert status == 200 and any(value["route"] == "/api/tickets/{id}" for value in matches)
        modified = external / "src/main/java/example/helpdesk/AssignmentGateway.java"
        modified.write_text(modified.read_text(encoding="utf-8") + "\n// source changed\n", encoding="utf-8")
        gateway = next(value for value in references if value["method"] == "lookup")
        assert request(agent, "/api/source-projects/" + binding["id"] + "/excerpts/" + gateway["id"])[0] == 409
        status, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, {"X-Triage-Source": "1"})
        assert status == 200 and binding["revision"] == 2
        assert request(agent, "/api/runs/" + failed["id"])[1]["sourceAnalysis"] == failed["sourceAnalysis"]
        if args.protocol_v3:
            assert request(agent, "/api/history/endpoints?service=ticket-service")[1]
            assert request(agent, "/api/runs/" + healthy["id"])[1]["endpoint"] == healthy_endpoint
        alternative = external / "src/main/java/example/helpdesk/ArchivedTicketService.java"
        alternative.write_text("package example.helpdesk; import java.util.Map; class ArchivedTicketService implements TicketService { public Map<String,Object> find(String id) { return Map.of(); } }\n", encoding="utf-8")
        status, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, {"X-Triage-Source": "1"})
        ambiguous = investigate()["sourceAnalysis"]["graph"]
        interface_edges = [edge for edge in ambiguous["edges"] if edge["call"] == "service.find"]
        assert len(interface_edges) == 1 and interface_edges[0]["resolution"] == "AMBIGUOUS" and len(interface_edges[0]["targetIds"]) == 2
        # Exercise management using only this generated source binding; no files are deleted.
        endpoint = agent + "/api/source-projects/" + binding["id"]
        data = json.dumps({"revision": binding["revision"], "name": binding["name"], "directory": str(external), "service": None}).encode()
        with urllib.request.urlopen(urllib.request.Request(endpoint, data=data, method="PUT", headers={"Content-Type": "application/json", "X-Triage-Source": "1"})) as response:
            unbound = json.load(response)
        assert unbound["service"] is None and not unbound["modelSharing"]
        assert not request(agent, "/api/config?service=ticket-service")[1]["sourceProject"]["available"]
        confirmation = json.dumps({"confirmId": binding["id"], "revision": unbound["revision"]}).encode()
        with urllib.request.urlopen(urllib.request.Request(endpoint, data=confirmation, method="DELETE", headers={"Content-Type": "application/json", "X-Triage-Source": "1"})) as response:
            assert response.status == 204
        assert modified.is_file() and request(agent, "/api/runs/" + failed["id"])[1]["sourceAnalysis"] == failed["sourceAnalysis"]
        summary = {"normal": "passed", "actualTimeouts": 2, "protocol": 3 if args.protocol_v3 else 1, "sourceFiles": binding["files"], "routeReference": "passed",
            "changedFileRejected": True, "reindexRevision": binding["revision"], "staticChain": "passed", "ambiguousImplementation": "passed", "indexManagement": "passed", "modelCalls": 0}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Source smoke passed: independent helpdesk JVM, actual timeouts, Controller/interface/Gateway/HTTP chain, verified call lines, ambiguity, evidence links, index management and history")
        if args.protocol_v3:
            print("Endpoint smoke passed: actual MVC handlers, healthy/timeout isolation, observed failure lines, source entry, saved scope, history filters and statistics")
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        server.shutdown()
        server.server_close()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
