#!/usr/bin/env python3
"""Run an independent helpdesk app, actual HTTP timeouts and local source references."""
import argparse
import json
import os
import shutil
import socket
import subprocess
import threading
import time
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
    parser.add_argument("--base-port", type=int, default=18320)
    parser.add_argument("--serve-assignment", action="store_true", help="Keep only the local assignment fixture running")
    parser.add_argument("--slow", action="store_true")
    args = parser.parse_args()
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
      protocol: OBSERVATIONS_V1
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

    def investigate():
        status, value = request(agent, "/api/runs", {"question": "工单接口 /api/tickets/{id} 为什么慢？", "service": "ticket-service",
            "windowMinutes": 5, "includeSource": True, "expectedSourceRevision": binding["revision"]}, {"X-Triage-Source": "1"})
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
        start(ROOT / "verification/ticket-service", "ticket", [f"--server.port={ports[1]}", f"--triage.sdk.downstream-base-url={dependency}"])
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
        assert status == 200 and binding["files"] == 3 and not binding["modelSharing"], (status, binding)
        normal = investigate()
        assert evidence(normal, "read_service_metrics")["data"]["timeoutCount"] == 0
        AssignmentHandler.slow = True
        for _ in range(2):
            assert request(ticket, "/api/tickets/T-1")[0] == 504
        failed = investigate()
        assert evidence(failed, "read_service_metrics")["data"]["timeoutCount"] == 2
        excerpts = failed["sourceAnalysis"]["excerpts"]
        assert any(value["method"] == "lookup" and "retrieve" in value["content"] for value in excerpts)
        status, matches = request(agent, "/api/source-projects/" + binding["id"] + "/search?q=%2Fapi%2Ftickets")
        assert status == 200 and any(value["route"] == "/api/tickets/{id}" for value in matches)
        modified = external / "src/main/java/example/helpdesk/AssignmentGateway.java"
        modified.write_text(modified.read_text(encoding="utf-8") + "\n// source changed\n", encoding="utf-8")
        gateway = next(value for value in excerpts if value["method"] == "lookup")
        assert request(agent, "/api/source-projects/" + binding["id"] + "/excerpts/" + gateway["id"])[0] == 409
        status, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, {"X-Triage-Source": "1"})
        assert status == 200 and binding["revision"] == 2
        assert request(agent, "/api/runs/" + failed["id"])[1]["sourceAnalysis"] == failed["sourceAnalysis"]
        summary = {"normal": "passed", "actualTimeouts": 2, "sourceFiles": binding["files"], "routeReference": "passed",
            "changedFileRejected": True, "reindexRevision": binding["revision"], "modelCalls": 0}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Source smoke passed: independent helpdesk JVM, actual HTTP requests/timeouts, route and method references, verified lines, stale-file rejection and history")
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
