#!/usr/bin/env python3
"""Verify V5 against a pinned Spring Petclinic REST checkout and two loopback HTTP services."""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import threading
import time
from urllib.parse import urlencode

from live_smoke import evidence, request, valid_citations
from package_release import ROOT, project
from prepare_petclinic_rest import REVISION


class Inventory(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/delay":
            self.send_error(404)
            return
        time.sleep(.35)
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


class Payment(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/payment?fail=false", "/payment?fail=true"):
            self.send_error(404)
            return
        self.send_response(500 if self.path.endswith("true") else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"{}")

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-directory", required=True, type=Path)
    parser.add_argument("--base-port", type=int, default=18720)
    args = parser.parse_args()
    public = args.project_directory.resolve()
    assert subprocess.check_output(["git", "-C", str(public), "rev-parse", "HEAD"], text=True).strip() == REVISION
    assert subprocess.check_output(["git", "-C", str(public), "diff", "--name-only"], text=True).splitlines() == ["pom.xml"]
    for name in ("RestTriageVerification.java", "RestTriageMultiVerification.java"):
        relative = Path("src/main/java/org/springframework/samples/petclinic/triage") / name
        assert (public / relative).read_bytes() == (ROOT / "verification/petclinic-rest" / name).read_bytes()
    if not 1024 <= args.base_port <= 65532:
        parser.error("base-port must be 1024..65532")
    for port in range(args.base_port, args.base_port + 4):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    agent = f"http://127.0.0.1:{args.base_port}"
    application = f"http://127.0.0.1:{args.base_port + 1}"
    inventory = f"http://127.0.0.1:{args.base_port + 2}"
    payment = f"http://127.0.0.1:{args.base_port + 3}"
    output = ROOT / "target/petclinic-multi-acceptance" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    services = output / "services.yml"
    services.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: petclinic-rest-service
      name: Spring Petclinic REST
      base-url: {application}/petclinic
      protocol: OBSERVATIONS_V5
      max-window-minutes: 15
      downstreams:
        - id: inventory-service
          name: 库存服务
        - id: payment-service
          name: 支付服务
""", encoding="utf-8")
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    children, handles, servers, threads = [], [], [], []

    def start(name, jar, options):
        assert jar.is_file(), f"Build {name} first"
        runtime = output / (name + ".jar")
        shutil.copyfile(jar, runtime)
        stream = (output / (name + ".log")).open("wb")
        handles.append(stream)
        children.append(subprocess.Popen([java, "-jar", str(runtime), *options], cwd=ROOT,
            stdout=stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0))

    try:
        for handler, port in ((Inventory, args.base_port + 2), (Payment, args.base_port + 3)):
            server = ThreadingHTTPServer(("127.0.0.1", port), handler)
            server.daemon_threads = True
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            servers.append(server)
            threads.append(thread)

        start("petclinic-rest", public / "target/spring-petclinic-rest-3.4.0.jar", [
            f"--server.port={args.base_port + 1}", "--server.address=127.0.0.1",
            "--triage.sdk.enabled=true", "--triage.sdk.service-id=petclinic-rest-service",
            "--triage.sdk.request-path-prefix=/api/", "--triage.sdk.endpoint-observations=true",
            "--triage.sdk.downstreams[0].id=inventory-service", f"--triage.sdk.downstreams[0].base-url={inventory}",
            "--triage.sdk.downstreams[1].id=payment-service", f"--triage.sdk.downstreams[1].base-url={payment}",
            "--triage.verification.multi-enabled=true", f"--triage.verification.inventory-base-url={inventory}",
            f"--triage.verification.payment-base-url={payment}"])
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

        headers = {"X-Triage-Lab": "1"}
        for fail, result in (("false", "ok"), ("true", "serverError")):
            status, body = request(application, "/petclinic/api/triage-multi/check?failPayment=" + fail, headers=headers)
            assert status == 200 and body == {"inventory": "timeout", "payment": result}, (status, body)

        query = urlencode({"windowMinutes": 5, "endTime": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")})
        status, observed = request(application, "/petclinic/triage/endpoint-observations?" + query)
        assert status == 200 and observed["schemaVersion"] == 5 and observed["requestCount"] == 2, (status, observed)
        rows = {row["id"]: row for row in observed["downstreams"]}
        assert rows["inventory-service"]["timeoutCount"] == 2
        assert rows["inventory-service"]["responseStatuses"]["unknown"] == 2
        assert rows["payment-service"]["timeoutCount"] == 0
        assert rows["payment-service"]["responseStatuses"]["successful"] == 1
        assert rows["payment-service"]["responseStatuses"]["serverError"] == 1

        status, run = request(agent, "/api/runs", {"question": "Petclinic REST 验收请求为什么变慢？",
            "service": "petclinic-rest-service", "windowMinutes": 5})
        assert status == 202, (status, run)
        deadline = time.monotonic() + 20
        while run["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.1)
            _, run = request(agent, "/api/runs/" + run["id"])
        assert run["status"] == "SUCCEEDED" and valid_citations(run), (run["status"], run.get("failure"))
        metrics = evidence(run, "read_service_metrics")
        assert metrics["data"]["observationType"] == "HTTP_DOWNSTREAMS"
        metrics_rows = {row["id"]: row for row in metrics["data"]["downstreams"]}
        assert metrics_rows["inventory-service"]["timeoutCount"] == 2
        assert metrics_rows["payment-service"]["responseStatuses"]["serverError"] == 1
        assert "库存服务" in run["diagnosis"]["possibleCauses"][0]["text"]
        assert "支付服务" not in run["diagnosis"]["possibleCauses"][0]["text"]
        _, saved = request(agent, "/api/runs/" + run["id"])
        assert saved["serviceInfo"]["downstreams"] and saved["evidence"] == run["evidence"]
        (output / "summary.json").write_text(json.dumps({"upstreamRevision": REVISION, "runId": run["id"],
            "requestCount": observed["requestCount"], "downstreams": observed["downstreams"],
            "runStatus": run["status"]}, ensure_ascii=False, indent=2), encoding="utf-8")
        print("Petclinic REST V5 passed: inventory timeout 2, payment 2xx 1 / 5xx 1, saved Agent evidence")
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
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=2)


if __name__ == "__main__":
    main()
