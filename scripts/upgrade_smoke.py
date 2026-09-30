#!/usr/bin/env python3
"""Check a prior release to current restart against one isolated local data directory."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

from package_release import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-jar", required=True, type=Path)
    parser.add_argument("--new-jar", required=True, type=Path)
    parser.add_argument("--port", type=int, default=18720)
    args = parser.parse_args()
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

    def request(path, body=None, settings=False):
        headers = {"Content-Type": "application/json"}
        if settings:
            headers["X-Triage-Settings"] = "1"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers), timeout=15) as response:
            return json.load(response)

    def start(jar, label):
        log = (output / f"{label}.log").open("wb")
        command = [java, "-jar", str(jar), f"--server.port={args.port}", "--server.address=127.0.0.1",
                   "--triage.mode=DEMO", f"--spring.datasource.url=jdbc:h2:file:{database}",
                   f"--triage.settings.key-file={key_file}"]
        child = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 75
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise RuntimeError(f"{label} exited during startup; see {output / (label + '.log')}")
                try:
                    if request("/api/config")["mode"] == "DEMO":
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
        child.terminate()
        try:
            child.wait(timeout=15)
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait(timeout=5)
        finally:
            log.close()

    def run():
        value = request("/api/runs", {"question": "订单查询接口为什么变慢了？", "service": "order-service",
                                          "windowMinutes": 15, "scenario": "DOWNSTREAM_TIMEOUT"})
        deadline = time.monotonic() + 20
        while value["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.1)
            value = request("/api/runs/" + value["id"])
        assert value["status"] == "SUCCEEDED" and value["mode"] == "DEMO" and value["synthetic"] is True
        return value

    old, old_log = start(old_jar, "prior")
    try:
        original = run()
        provider = request("/api/settings/providers", {"displayName": "升级测试", "protocol": "OPENAI_COMPATIBLE",
            "baseUrl": "http://127.0.0.1:1", "model": "upgrade-probe", "apiKey": "fixture",
            "temperature": 0.2, "timeoutSeconds": 4, "maxRounds": 4, "maxTokens": 1600, "version": 0}, settings=True)
        assert provider["keyConfigured"] is True and key_file.is_file()
        key_digest = hashlib.sha256(key_file.read_bytes()).hexdigest()
        assert '"fixture"' not in json.dumps(request("/api/settings"))
    finally:
        stop(old, old_log)

    upgraded, upgraded_log = start(new_jar, "current")
    try:
        saved = request("/api/runs/" + original["id"])
        assert saved["status"] == original["status"] and saved["question"] == original["question"]
        settings = request("/api/settings")
        assert any(item["id"] == provider["id"] and item["keyConfigured"] for item in settings["providers"])
        assert '"fixture"' not in json.dumps(settings)
        assert hashlib.sha256(key_file.read_bytes()).hexdigest() == key_digest
        newer = run()
        listed = {item["id"] for item in request("/api/runs?limit=50")}
        assert {original["id"], newer["id"]} <= listed
        assert settings["selection"]["mode"] == "DEMO"
        assert request("/api/history/retention?days=30")["eligibleCount"] == 0
    finally:
        stop(upgraded, upgraded_log)

    with socket.socket() as probe:
        probe.settimeout(1)
        assert probe.connect_ex(("127.0.0.1", args.port)) != 0, "Upgrade smoke process did not stop"
    print("Upgrade passed: prior history, encrypted provider, local key and current run; no automatic cleanup or model calls")
    print(f"Isolated data and logs: {output}")


if __name__ == "__main__":
    main()
