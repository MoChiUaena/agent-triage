#!/usr/bin/env python3
"""Verify a pinned public Petclinic application with real MVC requests and local source evidence."""
import argparse
import json
import os
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from package_release import ROOT, project
from live_smoke import request, evidence, valid_citations

REVISION = "67643c4137eb75bfeb177b427f8459c471bdcbd8"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-directory", required=True, type=Path)
    parser.add_argument("--base-port", type=int, default=18460)
    parser.add_argument("--keep-running", action="store_true", help="Keep this isolated preview after a successful check")
    args = parser.parse_args()
    public = args.project_directory.resolve()
    revision = subprocess.check_output(["git", "-C", str(public), "rev-parse", "HEAD"], text=True).strip()
    assert revision == REVISION, "Use the documented upstream commit"
    assert subprocess.check_output(["git", "-C", str(public), "diff", "--name-only"], text=True).splitlines() == ["pom.xml"], "Business source must be unchanged"
    if not 1024 <= args.base_port <= 65534:
        parser.error("base-port must be 1024..65534")
    for port in (args.base_port, args.base_port + 1):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    agent = f"http://127.0.0.1:{args.base_port}"
    application = f"http://127.0.0.1:{args.base_port + 1}"
    output = ROOT / "target/petclinic-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    configuration = output / "services.yml"
    configuration.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: petclinic-service
      name: Spring Petclinic
      downstream-id: unobserved-http
      downstream-name: 未采集的 HTTP 下游
      base-url: {application}
      protocol: OBSERVATIONS_V3
      max-window-minutes: 15
""", encoding="utf-8")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    children, handles = [], []
    retained = False
    headers = {"X-Triage-Source": "1"}

    def start(name, jar, options):
        assert jar.is_file(), f"Build {name} first"
        runtime = output / (name + ".jar")
        shutil.copyfile(jar, runtime)
        stream = (output / (name + ".log")).open("wb")
        handles.append(stream)
        children.append(subprocess.Popen([java, "-jar", str(runtime), *options], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))

    def business(path, expected):
        try:
            with urllib.request.urlopen(application + path, timeout=15) as response:
                status = response.status
                response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            error.read()
        assert status == expected, (path, status)

    def investigate(endpoint, label):
        status, run = request(agent, "/api/runs", {"question": label + "，服务请求为什么变慢？", "service": "petclinic-service",
            "windowMinutes": 5, "endpointId": endpoint["id"], "includeSource": True,
            "expectedSourceRevision": binding["revision"], "expectedSourceProjectId": binding["id"]}, headers)
        assert status == 202, status
        deadline = time.monotonic() + 15
        while run["status"] in ("RUNNING", "QUEUED") and time.monotonic() < deadline:
            time.sleep(.1)
            _, run = request(agent, "/api/runs/" + run["id"])
        assert run["status"] in ("SUCCEEDED", "INSUFFICIENT_EVIDENCE") and valid_citations(run), run.get("failure")
        assert not run["sourceAnalysis"]["modelUsed"]
        (output / (label + ".json")).write_text(json.dumps(run, ensure_ascii=False, indent=2), encoding="utf-8")
        return run

    try:
        start("petclinic", public / "target/spring-petclinic-3.5.0-SNAPSHOT.jar", [f"--server.port={args.base_port + 1}", "--server.address=127.0.0.1",
            "--triage.sdk.enabled=true", "--triage.sdk.service-id=petclinic-service", "--triage.sdk.downstream-id=unobserved-http",
            "--triage.sdk.downstream-base-url=http://127.0.0.1:1", "--triage.sdk.request-path-prefix=/owners/",
            "--triage.sdk.endpoint-observations=true", "--triage.sdk.exception-locations=true", "--triage.sdk.source-version-checks=true",
            "--triage.sdk.application-packages=org.springframework.samples.petclinic"])
        artifact, version = project(ROOT)
        start("agent", ROOT / "target" / f"{artifact}-{version}.jar", [f"--server.port={args.base_port}", "--triage.mode=DEMO",
            f"--spring.config.additional-location={configuration.as_uri()}",
            f"--spring.datasource.url=jdbc:h2:file:{output / 'history'}", f"--triage.settings.key-file={output / 'local.key'}"])
        deadline = time.monotonic() + 90
        while True:
            assert all(child.poll() is None for child in children), f"A preview stopped; see {output}"
            try:
                if request(application, "/actuator/health")[0] == 200 and request(agent, "/api/config")[0] == 200:
                    break
            except (OSError, ValueError):
                pass
            if time.monotonic() > deadline:
                raise TimeoutError(f"Preview startup timed out; see {output}")
            time.sleep(.5)
        assert request(agent, "/api/config")[1]["mode"] == "DEMO"
        status, binding = request(agent, "/api/source-projects", {"name": "Spring Petclinic 源码", "service": "petclinic-service", "directory": str(public)}, headers)
        assert status == 200 and binding["files"] > 10 and binding["parseFailures"] == 0 and not binding["modelSharing"]
        _, empty = request(agent, "/api/services/petclinic-service/source-check?windowMinutes=5")
        assert empty["requestCount"] == 0
        for _ in range(2):
            business("/owners/new", 200)
            business("/owners/1", 200)
            business("/owners/999999", 500)
        _, choices = request(agent, "/api/services/petclinic-service/endpoints?windowMinutes=5")
        endpoints = {item["endpoint"]["routeTemplate"]: item["endpoint"] for item in choices["endpoints"]}
        assert set(endpoints) == {"/owners/new", "/owners/{ownerId}"}
        normal = investigate(endpoints["/owners/new"], "Petclinic 正常页面")
        failed = investigate(endpoints["/owners/{ownerId}"], "Petclinic 业务异常")
        normal_metrics = evidence(normal, "read_service_metrics")["data"]
        failed_metrics = evidence(failed, "read_service_metrics")["data"]
        assert normal_metrics["requestCount"] == 2 and failed_metrics["requestCount"] == 4
        assert normal_metrics["timeoutCount"] == failed_metrics["timeoutCount"] == 0
        assert not evidence(normal, "query_error_logs")["data"]["entries"]
        assert len(evidence(failed, "query_error_logs")["data"]["entries"]) == 2
        assert failed["status"] == "INSUFFICIENT_EVIDENCE"
        assert "下游请求错误" not in json.dumps(failed["diagnosis"], ensure_ascii=False)
        graph = failed["sourceAnalysis"]["graph"]
        assert graph["endpointMatches"][0]["version"]["state"] == "MATCHED"
        matches = graph["failureMatches"]
        assert len(matches) == 2 and all(match["kind"] == "REQUEST_EXCEPTION" for match in matches)
        frames = [frame for match in matches for frame in match["frames"]]
        assert any(frame["state"] == "LINE_MATCH" and frame["frame"]["methodName"] == "findOwner" for frame in frames)
        assert any(frame["state"] == "UNMATCHED" and frame["frame"]["methodName"].startswith("lambda$") for frame in frames)
        for frame in frames:
            for excerpt in frame["excerpts"]:
                lines = (public / excerpt["path"]).read_text(encoding="utf-8").splitlines()
                assert excerpt["content"] == "\n".join(lines[excerpt["startLine"]-1:excerpt["endLine"]])
        observed = json.dumps(evidence(failed, "query_error_logs")["data"], ensure_ascii=False)
        assert "Owner not found" not in observed and "999999" not in observed and str(public) not in observed
        owner = public / "src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"
        original = owner.read_bytes()
        try:
            owner.write_bytes(original + b"\n// local acceptance: source differs from running build\n")
            _, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, headers)
            different = investigate(endpoints["/owners/{ownerId}"], "Petclinic 源码版本核对")
            assert different["sourceAnalysis"]["state"] == "SOURCE_VERSION_DIFFERENT"
            assert different["sourceAnalysis"]["graph"]["nodes"] == []
            assert request(agent, "/api/runs/" + failed["id"])[1]["sourceAnalysis"] == failed["sourceAnalysis"]
        finally:
            owner.write_bytes(original)
            _, binding = request(agent, "/api/source-projects/" + binding["id"] + "/reindex", {}, headers)
        _, restored = request(agent, "/api/services/petclinic-service/source-check?windowMinutes=5")
        (output / "source-check.json").write_text(json.dumps(restored, ensure_ascii=False, indent=2), encoding="utf-8")
        _, statistics = request(agent, "/api/statistics?days=7&service=petclinic-service")
        assert statistics["total"] == 3 and statistics["modelCalls"] == 0
        summary = {"upstreamCommit": revision, "mode": "DEMO", "observation": "LIVE", "requests": 6, "businessErrors": 2,
            "indexedFiles": binding["files"], "indexedSymbols": binding["symbols"], "parseFailures": binding["parseFailures"],
            "normalRun": normal["id"], "errorRun": failed["id"], "differentRun": different["id"],
            "entryVersion": graph["endpointMatches"][0]["version"]["state"], "sourceCheck": restored["state"],
            "failurePositions": [{"method": f["frame"]["methodName"], "line": f["frame"]["lineNumber"], "state": f["state"], "version": f["version"]["state"]} for f in matches[0]["frames"]],
            "agentUrl": agent, "applicationUrl": application, "pids": [child.pid for child in children]}
        (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Petclinic passed: original MVC pages, actual application exceptions, local source lines, build mismatch, frozen history and zero model calls")
        print(f"Saved isolated verification to {output}")
        if args.keep_running:
            retained = True
            print(f"Preview: {agent}; public application: {application}")
    finally:
        if not retained:
            for child in children:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
        for handle in handles:
            handle.close()


if __name__ == "__main__":
    main()
