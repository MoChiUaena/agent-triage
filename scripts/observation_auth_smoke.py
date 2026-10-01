#!/usr/bin/env python3
"""Verify optional observation tokens and a two-step rotation with separate JVMs."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request

from live_smoke import request
from package_release import ROOT, project


def http_status(base, path, token=None):
    headers = {} if token is None else {"X-Triage-Observation-Token": token}
    call = urllib.request.Request(base + path, headers=headers)
    try:
        with urllib.request.urlopen(call, timeout=5) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as error:
        error.read()
        return error.code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-port", type=int, default=18780)
    args = parser.parse_args()
    if not 1024 <= args.base_port <= 65533:
        parser.error("base-port must be 1024..65533")
    for port in (args.base_port, args.base_port + 1, args.base_port + 2):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))

    old_agent = f"http://127.0.0.1:{args.base_port}"
    new_agent = f"http://127.0.0.1:{args.base_port + 1}"
    application = f"http://127.0.0.1:{args.base_port + 2}"
    current, previous = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    output = ROOT / "target/observation-auth-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    configs = []
    for name, token in (("old", previous), ("new", current)):
        path = output / (name + "-services.yml")
        path.write_text(f"""triage:
  observation:
    source: LIVE
  services:
    - id: catalog-service
      name: Catalog
      downstream-id: inventory-service
      downstream-name: Inventory
      base-url: {application}
      protocol: OBSERVATIONS_V1
      access-token: {token}
""", encoding="utf-8")
        configs.append(path)
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else "java"
    processes, active, streams = [], set(), []

    def start(name, directory, options):
        artifact, version = project(directory)
        jar = directory / "target" / f"{artifact}-{version}.jar"
        assert jar.is_file(), f"Build {artifact} first"
        stream = (output / (name + ".log")).open("wb")
        streams.append(stream)
        child = subprocess.Popen([java, "-jar", str(jar), *options], cwd=ROOT,
            stdout=stream, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        processes.append(child)
        active.add(child)
        return child

    def stop(child):
        if child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
        active.discard(child)

    def catalog(previous_token=None, name="catalog"):
        options = [f"--server.port={args.base_port + 2}", "--server.address=127.0.0.1",
            f"--triage.sdk.observation-access-token={current}"]
        if previous_token is not None:
            options.append(f"--triage.sdk.observation-previous-token={previous_token}")
        return start(name, ROOT / "catalog-service", options)

    def agent(name, port, config):
        return start(name, ROOT, [f"--server.port={port}", "--server.address=127.0.0.1", "--triage.mode=DEMO",
            f"--spring.config.additional-location={config.as_uri()}",
            f"--spring.datasource.url=jdbc:h2:file:{output / name}-history",
            f"--triage.settings.key-file={output / (name + '-key')}"])

    def service_state(base):
        status, value = request(base, "/api/config?service=catalog-service")
        if status != 200:
            return None
        assert current not in json.dumps(value) and previous not in json.dumps(value)
        return value["observationAvailable"]

    path = "/triage/observations?windowMinutes=5&endTime=" + datetime.now(timezone.utc).strftime("%Y-%m-%dT%H%%3A%M%%3A%SZ")

    def wait_until(check, description):
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            assert all(child.poll() is None for child in active), \
                f"A verification process stopped; see {output}"
            try:
                if check():
                    return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.5)
        raise TimeoutError(f"{description}; see {output}")

    original_catalog = None
    try:
        original_catalog = catalog(previous)
        agent("old-agent", args.base_port, configs[0])
        agent("new-agent", args.base_port + 1, configs[1])
        wait_until(lambda: http_status(application, path) == 403 and service_state(old_agent) and service_state(new_agent),
            "Initial authorized observations unavailable")
        assert http_status(application, path, "wrong_" + "C" * 32) == 403
        assert http_status(application, path, previous) == 200
        assert http_status(application, path, current) == 200
        assert http_status(application, "/triage/database-observations?windowMinutes=5&endTime=2026-10-01T00%3A00%3A00Z") == 403

        stop(original_catalog)
        catalog(None, "catalog-rotated")
        wait_until(lambda: http_status(application, path, current) == 200, "Rotated catalog did not start")
        assert http_status(application, path, previous) == 403
        wait_until(lambda: not service_state(old_agent) and service_state(new_agent),
            "Old Agent token remained accepted after rotation")
        print("Observation access passed: loopback-only read routes, overlapping tokens, old-token revocation and two independent Agents")
    finally:
        for child in reversed(processes):
            stop(child)
        for stream in streams:
            stream.close()
        for path in configs:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
