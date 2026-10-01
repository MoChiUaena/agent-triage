#!/usr/bin/env python3
"""Check the pinned Petclinic REST application through its original context path."""
import argparse
from datetime import datetime, timezone
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

from package_release import ROOT, project
from live_smoke import evidence, request, valid_citations
from prepare_petclinic_rest import REVISION


class DelayedDownstream(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/delay":
            self.send_error(404)
            return
        time.sleep(.45)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")
        except OSError:
            pass  # The client has already timed out.

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-directory", required=True, type=Path)
    parser.add_argument("--base-port", type=int, default=18640)
    args = parser.parse_args()
    public = args.project_directory.resolve()
    assert subprocess.check_output(["git", "-C", str(public), "rev-parse", "HEAD"], text=True).strip() == REVISION
    assert subprocess.check_output(["git", "-C", str(public), "diff", "--name-only"], text=True).splitlines() == ["pom.xml"]
    support = public / "src/main/java/org/springframework/samples/petclinic/triage/RestTriageVerification.java"
    assert support.read_bytes() == (ROOT / "verification/petclinic-rest/RestTriageVerification.java").read_bytes()
    if not 1024 <= args.base_port <= 65533:
        parser.error("base-port must be 1024..65533")
    for port in (args.base_port, args.base_port + 1, args.base_port + 2):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    agent = f"http://127.0.0.1:{args.base_port}"
    application = f"http://127.0.0.1:{args.base_port + 1}"
    downstream = f"http://127.0.0.1:{args.base_port + 2}"
    output = ROOT / "target/petclinic-rest-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    services = output / "services.yml"
    services.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: petclinic-rest-service
      name: Spring Petclinic REST
      downstream-id: verification-downstream
      downstream-name: 验收 HTTP 下游
      base-url: {application}/petclinic
      protocol: OBSERVATIONS_V3
      max-window-minutes: 15
""", encoding="utf-8")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    starter_name, starter_version = project(ROOT / "triage-spring-boot-starter")
    class_agent = ROOT / "triage-spring-boot-starter/target" / f"{starter_name}-{starter_version}-agent.jar"
    assert class_agent.is_file(), "Build the observation Java Agent first"
    children, handles = [], []
    delayed = None
    delayed_thread = None

    def start(name, jar, options, java_options=()):
        assert jar.is_file(), f"Build {name} first"
        runtime = output / (name + ".jar")
        shutil.copyfile(jar, runtime)
        stream = (output / (name + ".log")).open("wb")
        handles.append(stream)
        children.append(subprocess.Popen([java, *java_options, "-jar", str(runtime), *options], cwd=ROOT,
            stdout=stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))

    def business(path, expected, headers=None):
        req = urllib.request.Request(application + "/petclinic" + path, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                status = response.status
                response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            error.read()
        assert status == expected, (path, status)

    headers = {"X-Triage-Source": "1"}

    def investigate(binding, endpoint, label):
        status, run = request(agent, "/api/runs", {"question": label + "的请求延迟或下游超时有哪些证据？请核对接口和源码入口。",
            "service": "petclinic-rest-service", "windowMinutes": 5, "endpointId": endpoint["id"],
            "includeSource": True, "expectedSourceRevision": binding["revision"],
            "expectedSourceProjectId": binding["id"]}, headers)
        assert status == 202, (status, run)
        deadline = time.monotonic() + 20
        while run["status"] in ("RUNNING", "QUEUED") and time.monotonic() < deadline:
            time.sleep(.1)
            _, run = request(agent, "/api/runs/" + run["id"])
        assert run["status"] in ("SUCCEEDED", "INSUFFICIENT_EVIDENCE") and valid_citations(run), run.get("failure")
        assert {item["source"] for item in run["evidence"]} >= {"read_service_metrics", "query_error_logs"}, \
            (run["status"], [item["source"] for item in run["evidence"]])
        assert run["modelExecution"] is None and not run["sourceAnalysis"]["modelUsed"]
        (output / (label + ".json")).write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
        return run

    try:
        delayed = ThreadingHTTPServer(("127.0.0.1", args.base_port + 2), DelayedDownstream)
        delayed.daemon_threads = True
        delayed_thread = threading.Thread(target=delayed.serve_forever, daemon=True)
        delayed_thread.start()
        start("petclinic-rest", public / "target/spring-petclinic-rest-3.4.0.jar", [
            f"--server.port={args.base_port + 1}", "--server.address=127.0.0.1", "--triage.sdk.enabled=true",
            "--triage.sdk.service-id=petclinic-rest-service", "--triage.sdk.downstream-id=verification-downstream",
            f"--triage.sdk.downstream-base-url={downstream}", "--triage.sdk.request-path-prefix=/api/",
            "--triage.sdk.endpoint-observations=true", "--triage.sdk.response-status-counts=true", "--triage.sdk.exception-locations=true",
            "--triage.sdk.source-version-checks=true",
            "--triage.sdk.application-packages=org.springframework.samples.petclinic",
            "--triage.verification.enabled=true", f"--triage.verification.downstream-base-url={downstream}"], [f"-javaagent:{class_agent}"])
        artifact, version = project(ROOT)
        start("agent", ROOT / "target" / f"{artifact}-{version}.jar", [
            f"--server.port={args.base_port}", "--triage.mode=DEMO",
            f"--spring.config.additional-location={services.as_uri()}",
            f"--spring.datasource.url=jdbc:h2:file:{output / 'history'}",
            f"--triage.settings.key-file={output / 'local.key'}"])
        deadline = time.monotonic() + 90
        while True:
            assert all(child.poll() is None for child in children), f"A verification process stopped; see {output}"
            try:
                if request(application, "/petclinic/actuator/health")[0] == 200 \
                    and request(agent, "/api/config?service=petclinic-rest-service")[1]["observationAvailable"]:
                    break
            except (OSError, ValueError, KeyError):
                pass
            if time.monotonic() > deadline:
                raise TimeoutError(f"Petclinic REST startup timed out; see {output}")
            time.sleep(.5)

        status, binding = request(agent, "/api/source-projects", {"name": "Petclinic REST 源码",
            "service": "petclinic-rest-service", "directory": str(public)}, headers)
        assert status == 200 and binding["files"] > 20 and binding["parseFailures"] == 0 and not binding["modelSharing"]
        for _ in range(2):
            business("/api/owners/1", 200)
            business("/api/owners", 200)
        business("/api/owners/999999", 404)
        business("/api/triage-verification/error", 500, {"X-Triage-Lab": "1"})
        business("/api/triage-verification/timeout", 504, {"X-Triage-Lab": "1"})

        _, catalogue = request(agent, "/api/services/petclinic-rest-service/endpoints?windowMinutes=5")
        endpoints = {item["endpoint"]["routeTemplate"]: item["endpoint"] for item in catalogue["endpoints"]}
        original = endpoints["/api/owners/{ownerId}"]
        failure = endpoints["/api/triage-verification/error"]
        timeout = endpoints["/api/triage-verification/timeout"]
        assert original["handlerClass"].endswith("OwnerRestController")
        owner_run = investigate(binding, original, "Petclinic REST 主人详情")
        owner_metrics = evidence(owner_run, "read_service_metrics")["data"]
        assert owner_metrics["requestCount"] == 3 and owner_metrics["timeoutCount"] == 0
        assert owner_metrics["responseStatuses"] == {"informational": 0, "successful": 2, "redirection": 0,
            "clientError": 1, "serverError": 0, "unknown": 0}
        assert owner_run["status"] == "INSUFFICIENT_EVIDENCE" and owner_run["diagnosis"]["possibleCauses"] == []
        owner_logs = evidence(owner_run, "query_error_logs")["data"]
        assert owner_logs["returnedCount"] == 0 and owner_logs["entries"] == []
        entry = owner_run["sourceAnalysis"]["graph"]["endpointMatches"][0]
        graph = owner_run["sourceAnalysis"]["graph"]
        assert entry["state"] == "MATCHED" and entry["version"]["state"] == "MATCHED"
        assert any(node["excerpt"]["id"] in entry["sourceIds"] and node["excerpt"]["path"].endswith("OwnerRestController.java")
                   for node in graph["nodes"])
        status, missing_owner = request(agent, "/api/runs", {"question": "主人详情返回 HTTP 404 的原因是什么？",
            "service": "petclinic-rest-service", "windowMinutes": 5, "endpointId": original["id"]})
        assert status == 202, (status, missing_owner)
        deadline = time.monotonic() + 20
        while missing_owner["status"] in ("RUNNING", "QUEUED") and time.monotonic() < deadline:
            time.sleep(.1)
            _, missing_owner = request(agent, "/api/runs/" + missing_owner["id"])
        assert missing_owner["status"] == "INSUFFICIENT_EVIDENCE" and missing_owner["evidence"]
        assert missing_owner["diagnosis"]["possibleCauses"] == [] and missing_owner["modelExecution"] is None
        error_run = investigate(binding, failure, "Petclinic REST 验收异常")
        error_metrics = evidence(error_run, "read_service_metrics")["data"]
        assert error_metrics["responseStatuses"]["serverError"] == 1 and error_metrics["timeoutCount"] == 0
        frames = [frame for match in error_run["sourceAnalysis"]["graph"]["failureMatches"] for frame in match["frames"]]
        frame_states = [(frame["frame"]["className"], frame["state"], frame["version"]["state"]) for frame in frames]
        assert any("ProbeFailure" in name and version == "MATCHED" for name, _, version in frame_states), frame_states
        timeout_run = investigate(binding, timeout, "Petclinic REST 验收超时")
        timeout_metrics = evidence(timeout_run, "read_service_metrics")["data"]
        assert timeout_run["status"] == "SUCCEEDED" and timeout_metrics["timeoutCount"] == 1
        assert timeout_metrics["responseStatuses"]["serverError"] == 1 and timeout_metrics["responseStatuses"]["clientError"] == 0
        assert timeout_run["sourceAnalysis"]["graph"]["failureMatches"][0]["kind"] == "HTTP_CLIENT_FAILURE"

        owner = public / "src/main/java/org/springframework/samples/petclinic/rest/controller/OwnerRestController.java"
        original_source = owner.read_bytes()
        try:
            owner.write_bytes(original_source + b"\n// isolated acceptance: source differs from running build\n")
            _, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, headers)
            different = investigate(binding, original, "Petclinic REST 版本核对")
            assert different["sourceAnalysis"]["state"] == "SOURCE_VERSION_DIFFERENT"
            assert request(agent, "/api/runs/" + owner_run["id"])[1]["sourceAnalysis"] == owner_run["sourceAnalysis"]
        finally:
            owner.write_bytes(original_source)
            _, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, headers)
        print("Petclinic REST passed: original JSON routes and 404, isolated 5xx and downstream timeout, response classes and source versions; zero model calls")
        print(f"Isolated results: {output}")
    finally:
        for child in reversed(children):
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
        for stream in handles:
            stream.close()
        if delayed is not None:
            delayed.shutdown()
            delayed.server_close()
        if delayed_thread is not None:
            delayed_thread.join(timeout=2)


if __name__ == "__main__":
    main()
