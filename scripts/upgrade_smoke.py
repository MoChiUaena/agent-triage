#!/usr/bin/env python3
"""Check a prior release to current restart against one isolated local data directory."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import parse_qs, urlsplit

from package_release import ROOT
from live_smoke import valid_citations
from verify_release import version_number

FIXTURE_KEY = "fixture-upgrade-token"
ENDPOINT = {"httpMethod": "GET", "routeTemplate": "/api/upgrade/{id}", "handlerClass": "example.UpgradeController",
    "handlerMethod": "lookup", "parameterTypes": ["java.lang.String"], "stage": "MVC_SELECTED"}
identity = "\0".join((ENDPOINT["httpMethod"], ENDPOINT["routeTemplate"], ENDPOINT["handlerClass"], ENDPOINT["handlerMethod"], "java.lang.String"))
ENDPOINT["id"] = "EP-" + hashlib.sha256(identity.encode()).hexdigest()[:32]


def window_start(end, minutes):
    matched = re.fullmatch(r"(.+T\d{2}:\d{2}:\d{2})(\.[0-9]{1,9})?Z", end)
    if not matched:
        raise ValueError("Expected a UTC instant")
    seconds = datetime.fromisoformat(matched.group(1)).replace(tzinfo=timezone.utc) - timedelta(minutes=minutes)
    return seconds.strftime("%Y-%m-%dT%H:%M:%S") + (matched.group(2) or "") + "Z"


class UpgradeFixture(BaseHTTPRequestHandler):
    def payload(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlsplit(self.path)
        if path.path != "/triage/endpoint-observations":
            self.payload(404, {"error": "Unknown fixture route"})
            return
        query = parse_qs(path.query)
        selected = query.get("endpointId", [None])[0]
        if selected not in (None, ENDPOINT["id"]):
            self.payload(409, {"error": "Unknown fixture endpoint"})
            return
        end, minutes = query["endTime"][0], int(query["windowMinutes"][0])
        value = {"schemaVersion": 3, "kind": "HTTP_ENDPOINTS", "service": "upgrade-service",
            "downstreamService": "upgrade-downstream", "windowStart": window_start(end, minutes), "windowEnd": end,
            "requestCount": 3, "timeoutCount": 0, "recordedRequestCount": 3, "requestP95Ms": 25, "downstreamP95Ms": 10,
            "downstreamTimeoutRate": 0, "baselineRequestP95Ms": None, "errors": [], "synthetic": False,
            "endpoint": ENDPOINT if selected else None, "endpoints": [{"endpoint": ENDPOINT, "requestCount": 3,
                "timeoutCount": 0, "requestP95Ms": 25, "downstreamP95Ms": 10}],
            "unattributedRequestCount": 0, "otherEndpointRequestCount": 0}
        if self.server.classified:
            statuses = {"informational": 0, "successful": 2, "redirection": 0,
                "clientError": 1, "serverError": 0, "unknown": 0}
            value["responseStatuses"] = statuses
            value["endpoints"][0]["responseStatuses"] = statuses
        self.payload(200, value)

    def do_POST(self):
        if self.path != "/chat/completions" or self.headers.get("Authorization") != "Bearer " + FIXTURE_KEY:
            self.payload(403, {"error": "Local probe authorization failed"})
            return
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= 32768:
            self.payload(413, {"error": "Invalid local probe size"})
            return
        value = json.loads(self.rfile.read(size))
        self.server.probe_calls += 1
        self.payload(200, {"id": "upgrade-local-probe", "created": 1, "model": value["model"],
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "ready"}}]})

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-jar", required=True, type=Path)
    parser.add_argument("--new-jar", required=True, type=Path)
    parser.add_argument("--prior-version", default="0.6.0", help="Published baseline; v0.9+ also seeds response classifications")
    parser.add_argument("--port", type=int, default=18720)
    args = parser.parse_args()
    prior_version = version_number(args.prior_version)
    old_jar, new_jar = args.old_jar.resolve(), args.new_jar.resolve()
    assert old_jar.is_file() and new_jar.is_file(), "Build or download both versions first"
    if not 1024 <= args.port <= 65535:
        parser.error("port must be 1024..65535")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", args.port))

    output = ROOT / "target/upgrade-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    key_file = output / "model-config.key"
    database = (output / "history").as_posix()
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    base = f"http://127.0.0.1:{args.port}"
    fixture = ThreadingHTTPServer(("127.0.0.1", 0), UpgradeFixture)
    fixture.daemon_threads = True
    fixture.probe_calls = 0
    fixture.classified = False
    fixture_thread = threading.Thread(target=fixture.serve_forever, daemon=True)
    fixture_thread.start()
    fixture_url = f"http://127.0.0.1:{fixture.server_port}"
    configuration = output / "services.yml"
    configuration.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: upgrade-service
      name: 升级服务
      downstream-id: upgrade-downstream
      downstream-name: 升级下游
      base-url: {fixture_url}
      protocol: OBSERVATIONS_V3
      max-window-minutes: 15
""", encoding="utf-8")
    configuration_digest = hashlib.sha256(configuration.read_bytes()).hexdigest()
    processes = []

    def request(path, body=None, settings=False, method=None):
        headers = {"Content-Type": "application/json"}
        if settings:
            headers["X-Triage-Settings"] = "1"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers, method=method), timeout=15) as response:
            return json.load(response)

    default_store = output / "default-store"
    default_store.mkdir()

    def start(jar, label, defaults=False):
        log = (output / f"{label}.log").open("wb")
        command = [java, "-jar", str(jar), f"--server.port={args.port}", "--server.address=127.0.0.1",
                   "--triage.mode=DEMO", f"--spring.config.additional-location={configuration.as_uri()}"]
        if not defaults:
            # Upgrade tests start from a fully committed prior store, rather than a simulated crash of the old release.
            command += [f"--spring.datasource.url=jdbc:h2:file:{database};WRITE_DELAY=0", f"--triage.settings.key-file={key_file}"]
        child = subprocess.Popen(command, cwd=default_store if defaults else ROOT, stdout=log, stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        processes.append((child, log))
        try:
            deadline = time.monotonic() + 75
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError(f"{label} exited during startup; see {output / (label + '.log')}")
                try:
                    config = request("/api/config?service=upgrade-service")
                    if config["mode"] in ("DEMO", "MODEL") and config["observationAvailable"]:
                        return child, log
                except (OSError, ValueError, KeyError):
                    pass
                time.sleep(.25)
            raise TimeoutError(f"{label} startup timed out; see {output / (label + '.log')}")
        except BaseException:
            child.terminate()
            child.wait(timeout=10)
            log.close()
            raise

    def stop(child, log):
        if child.poll() is not None:
            log.close()
            return
        child.terminate()
        try:
            child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=5)
        finally:
            log.close()

    def run(expected_status="SUCCEEDED"):
        value = request("/api/runs", {"question": "升级服务请求为什么变慢了？", "service": "upgrade-service",
            "windowMinutes": 5, "endpointId": ENDPOINT["id"]})
        deadline = time.monotonic() + 20
        while value["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.1)
            value = request("/api/runs/" + value["id"])
        assert value["status"] == expected_status, {"expectedStatus": expected_status, "status": value["status"], "failure": value.get("failure")}
        assert value["mode"] == "DEMO" and value["synthetic"] is False and valid_citations(value)
        return value

    try:
        old, old_log = start(old_jar, "prior")
        try:
            original = run()
            originals = [original]
            if prior_version >= (0, 9, 0):
                fixture.classified = True
                classified = run("INSUFFICIENT_EVIDENCE")
                assert classified["diagnosis"]["possibleCauses"] == []
                metrics = next(item["data"] for item in classified["evidence"] if item["source"] == "read_service_metrics")
                statuses = metrics["requestDetails"]["responseStatuses"]
                assert statuses["successful"] == 2 and statuses["clientError"] == 1
                assert metrics["requestDetails"].get("requestFailures") is None
                originals.append(classified)
                fixture.classified = False
            services = request("/api/config?service=upgrade-service")["services"]
            provider = request("/api/settings/providers", {"displayName": "升级测试", "protocol": "OPENAI_COMPATIBLE",
                "baseUrl": fixture_url, "model": "upgrade-probe", "apiKey": FIXTURE_KEY,
                "temperature": 0.2, "timeoutSeconds": 4, "maxRounds": 4, "maxTokens": 1600, "version": 0}, settings=True)
            assert provider["keyConfigured"] is True and key_file.is_file()
            key_digest = hashlib.sha256(key_file.read_bytes()).hexdigest()
            assert request(f"/api/settings/providers/{provider['id']}/test?version=1", {}, settings=True)["success"]
            request("/api/settings/selection", {"mode": "MODEL", "providerId": provider["id"]}, settings=True, method="PUT")
            original_settings = request("/api/settings")
            assert original_settings["selection"]["mode"] == "MODEL" and FIXTURE_KEY not in json.dumps(original_settings)
        finally:
            stop(old, old_log)

        upgraded, upgraded_log = start(new_jar, "current")
        try:
            for prior_run in originals:
                saved = request("/api/runs/" + prior_run["id"])
                assert saved == prior_run, "Saved execution changed during upgrade"
                details = next(item["data"]["requestDetails"] for item in saved["evidence"] if item["source"] == "read_service_metrics")
                assert details.get("requestFailures") is None, "Upgrade invented request failures in V3 history"
            old_details = next(item["data"]["requestDetails"] for item in originals[0]["evidence"] if item["source"] == "read_service_metrics")
            assert old_details.get("responseStatuses") is None
            settings = request("/api/settings")
            assert settings == original_settings, {"providerFieldsUnchanged": settings["providers"] == original_settings["providers"],
                "priorMode": original_settings["selection"]["mode"], "currentMode": settings["selection"]["mode"],
                "priorSource": original_settings["selection"]["source"], "currentSource": settings["selection"]["source"]}
            assert FIXTURE_KEY not in json.dumps(settings)
            assert hashlib.sha256(key_file.read_bytes()).hexdigest() == key_digest
            assert hashlib.sha256(configuration.read_bytes()).hexdigest() == configuration_digest
            assert request("/api/config?service=upgrade-service")["services"] == services
            assert request("/api/config?service=upgrade-service")["mode"] == "MODEL"
            assert request(f"/api/settings/providers/{provider['id']}/test?version=1", {}, settings=True)["success"]
            assert fixture.probe_calls == 2, "Both versions must decrypt and use the saved fixture key"
            request("/api/settings/selection", {"mode": "DEMO"}, settings=True, method="PUT")
            newer = run()
            metrics = next(item["data"] for item in newer["evidence"] if item["source"] == "read_service_metrics")
            assert "responseStatuses" not in metrics and metrics["requestDetails"].get("responseStatuses") is None
            listed = {item["id"] for item in request("/api/runs?limit=50")}
            assert {item["id"] for item in originals} | {newer["id"]} <= listed
            assert metrics["requestDetails"].get("requestFailures") is None
            assert request("/api/settings")["selection"]["mode"] == "DEMO"
            assert request("/api/history/retention?days=30")["eligibleCount"] == 0
        finally:
            stop(upgraded, upgraded_log)

        current, current_log = start(new_jar, "default-before-kill", defaults=True)
        default_history = run()
        default_provider = request("/api/settings/providers", {"displayName": "默认存储检查", "protocol": "OPENAI_COMPATIBLE",
            "baseUrl": fixture_url, "model": "upgrade-probe", "apiKey": FIXTURE_KEY,
            "temperature": 0.2, "timeoutSeconds": 4, "maxRounds": 4, "maxTokens": 1600, "version": 0}, settings=True)
        request("/api/settings/selection", {"mode": "MODEL", "providerId": default_provider["id"]}, settings=True, method="PUT")
        default_settings = request("/api/settings")
        default_key = default_store / "data/model-config.key"
        default_key_digest = hashlib.sha256(default_key.read_bytes()).hexdigest()
        current.kill()  # Deliberately abrupt on both operating systems; tests the new default store's durability.
        current.wait(timeout=10)
        current_log.close()
        reopened, reopened_log = start(new_jar, "default-after-kill", defaults=True)
        try:
            restored = request("/api/settings")
            assert restored == default_settings, {"priorMode": default_settings["selection"]["mode"],
                "currentMode": restored["selection"]["mode"], "currentSource": restored["selection"]["source"]}
            assert request("/api/runs/" + default_history["id"]) == default_history
            assert hashlib.sha256(default_key.read_bytes()).hexdigest() == default_key_digest
            assert request(f"/api/settings/providers/{default_provider['id']}/test?version=1", {}, settings=True)["success"]
            assert fixture.probe_calls == 3
        finally:
            stop(reopened, reopened_log)
    finally:
        for child, log in reversed(processes):
            stop(child, log)
        fixture.shutdown()
        fixture.server_close()
        fixture_thread.join(timeout=2)

    with socket.socket() as probe:
        probe.settimeout(1)
        assert probe.connect_ex(("127.0.0.1", args.port)) != 0, "Upgrade smoke process did not stop"
    print(f"Upgrade from {args.prior_version} passed: {len(originals)} unchanged V3 histories, classifications stay optional, config/key and active model selection retained; default H2 survives abrupt stop; local probes only")
    print(f"Isolated data and logs: {output}")


if __name__ == "__main__":
    main()
