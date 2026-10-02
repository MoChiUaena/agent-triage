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
    parser.add_argument("--classified-inbound", action="store_true", help="Compare classified V4 request failures with handled responses and async results")
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
    downstream_config = "" if args.classified_inbound else "      downstream-id: verification-downstream\n      downstream-name: 验收 HTTP 下游\n"
    protocol = "HTTP_REQUESTS_V4" if args.classified_inbound else "OBSERVATIONS_V3"
    services.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: petclinic-rest-service
      name: Spring Petclinic REST
{downstream_config}      base-url: {application}/petclinic
      protocol: {protocol}
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
        assert status in (expected if isinstance(expected, tuple) else (expected,)), (path, status)

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
        sdk_options = ["--triage.sdk.kind=HTTP_REQUESTS", "--triage.sdk.request-failure-counts=true"] if args.classified_inbound else [
            "--triage.sdk.downstream-id=verification-downstream", f"--triage.sdk.downstream-base-url={downstream}"]
        start("petclinic-rest", public / "target/spring-petclinic-rest-3.4.0.jar", [
            f"--server.port={args.base_port + 1}", "--server.address=127.0.0.1", "--triage.sdk.enabled=true",
            "--triage.sdk.service-id=petclinic-rest-service", *sdk_options, "--triage.sdk.request-path-prefix=/api/",
            "--triage.sdk.endpoint-observations=true", "--triage.sdk.response-status-counts=true", "--triage.sdk.async-context-propagation=true", "--triage.sdk.exception-locations=true",
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
        if args.classified_inbound:
            check_classified_inbound(application, agent, output, binding, business, investigate)
            return
        for _ in range(2):
            business("/api/owners/1", 200)
            business("/api/owners", 200)
        business("/api/owners/999999", 404)
        business("/api/triage-verification/error", 500, {"X-Triage-Lab": "1"})
        business("/api/triage-verification/timeout", 504, {"X-Triage-Lab": "1"})
        for route in ("callable-timeout", "deferred-timeout", "parallel-timeout"):
            business("/api/triage-verification/" + route, 504, {"X-Triage-Lab": "1"})
        business("/api/triage-verification/late-timeout", 204, {"X-Triage-Lab": "1"})

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
        for route in ("callable-timeout", "deferred-timeout", "parallel-timeout"):
            async_run = investigate(binding, endpoints["/api/triage-verification/" + route], "Petclinic REST " + route)
            async_metrics = evidence(async_run, "read_service_metrics")["data"]
            assert async_run["status"] == "SUCCEEDED" and async_metrics["requestCount"] == async_metrics["timeoutCount"] == 1
            assert async_metrics["responseStatuses"]["serverError"] == 1 and async_metrics["responseStatuses"]["unknown"] == 0
            assert async_run["sourceAnalysis"]["graph"]["failureMatches"][0]["kind"] == "HTTP_CLIENT_FAILURE"
            if route == "parallel-timeout":
                assert async_metrics["downstreamP95Ms"] >= 250, "Both 150ms calls must contribute to the cumulative time"
        deadline = time.monotonic() + 5
        while request(application, "/petclinic/triage-verification/late-finished", headers={"X-Triage-Lab": "1"})[1]["completed"] < 1:
            assert time.monotonic() < deadline, "Late verification call did not finish"
            time.sleep(.05)
        late_run = investigate(binding, endpoints["/api/triage-verification/late-timeout"], "Petclinic REST 迟到调用")
        late_metrics = evidence(late_run, "read_service_metrics")["data"]
        assert late_metrics["requestCount"] == 1 and late_metrics["timeoutCount"] == 0 and late_metrics["downstreamP95Ms"] == 0
        assert late_metrics["responseStatuses"]["successful"] == 1 and evidence(late_run, "query_error_logs")["data"]["returnedCount"] == 0

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
        print("Petclinic REST passed: original routes, response classes, explicit DeferredResult and opted-in Callable, parallel and late calls, source versions; zero model calls")
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


def check_classified_inbound(application, agent, output, binding, business, investigate):
    marker = {"X-Triage-Lab": "1"}
    for _ in range(2): business("/api/owners/1", 200)
    business("/api/owners/999999", 404)
    for route, status in [("error", 500), ("plain-server-error", 503), ("timeout", 504), ("callable-error", 500),
                          ("servlet-timeout", (500, 503)), ("late-timeout", 204)]:
        business("/api/triage-verification/" + route, status, marker)
    business("/api/triage-verification/recovery", 500, {**marker, "X-Triage-Fail": "1"})
    business("/api/triage-verification/recovery", 204, marker)
    deadline = time.monotonic() + 5
    while request(application, "/petclinic/triage-verification/late-finished", headers=marker)[1]["completed"] < 1:
        assert time.monotonic() < deadline, "Late verification call did not finish"
        time.sleep(.05)
    _, catalogue = request(agent, "/api/services/petclinic-rest-service/endpoints?windowMinutes=5")
    endpoints = {item["endpoint"]["routeTemplate"]: item["endpoint"] for item in catalogue["endpoints"]}
    cases = [("/api/owners/{ownerId}", "原接口404", "INSUFFICIENT_EVIDENCE", "executionFailures", 0),
        ("/api/triage-verification/error", "同步执行异常", "SUCCEEDED", "executionFailures", 1),
        ("/api/triage-verification/plain-server-error", "单纯503", "INSUFFICIENT_EVIDENCE", "serverErrorResponses", 1),
        ("/api/triage-verification/timeout", "捕获下游超时后的504", "INSUFFICIENT_EVIDENCE", "serverErrorResponses", 1),
        ("/api/triage-verification/callable-error", "Callable执行异常", "SUCCEEDED", "executionFailures", 1),
        ("/api/triage-verification/servlet-timeout", "Servlet异步超时", "INSUFFICIENT_EVIDENCE", "asyncTimeouts", 1),
        ("/api/triage-verification/late-timeout", "响应完成后的迟到调用", "INSUFFICIENT_EVIDENCE", "executionFailures", 0),
        ("/api/triage-verification/recovery", "异常后成功恢复", "SUCCEEDED", "executionFailures", 1)]
    report = []
    for route, label, expected, category, count in cases:
        run = investigate(binding, endpoints[route], "混合对照 " + label)
        data = evidence(run, "read_service_metrics")["data"]
        assert run["status"] == expected, (label, run["status"], run.get("failure"))
        assert data["requestFailures"][category] == count, (label, data["requestFailures"])
        assert not {"timeoutCount", "downstreamTimeoutRate", "downstreamP95Ms"} & data.keys()
        if category != "executionFailures": assert data["requestFailures"]["executionFailures"] == 0
        if expected == "SUCCEEDED":
            assert "请求执行" in run["diagnosis"]["possibleCauses"][0]["text"]
            assert "内部根因" in run["diagnosis"]["uncertainty"]
        else: assert not run["diagnosis"]["possibleCauses"]
        if label == "异常后成功恢复":
            assert data["requestCount"] == 2 and data["responseStatuses"]["successful"] == 1 and data["responseStatuses"]["serverError"] == 1
        assert request(agent, "/api/runs/" + run["id"])[1]["diagnosis"] == run["diagnosis"]
        report.append({"case": label, "status": run["status"], "requestFailures": data["requestFailures"], "responseStatuses": data["responseStatuses"]})
    (output / "mixed-summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Petclinic REST mixed V4 passed: original 404, execution exceptions, plain 5xx, caught downstream timeout, Callable, Servlet timeout, late result and recovery; zero model calls")


if __name__ == "__main__":
    main()
