#!/usr/bin/env python3
"""Exercise the extracted package's native launcher and stop only this launch."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path
import socket
import subprocess
import sys
import time

from live_smoke import request
from package_release import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle-directory", required=True, type=Path)
    parser.add_argument("--base-port", type=int, default=18840)
    args = parser.parse_args()
    bundle = args.bundle_directory.resolve()
    if not 1024 <= args.base_port <= 65523:
        parser.error("base-port must be 1024..65523")
    offsets = (0, 2, 4, 6, 8, 9, 10, 12)
    for offset in offsets:
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", args.base_port + offset))
    assert (bundle / "manifest.json").is_file(), "Extract the complete verified package first"
    output = ROOT / "target/bundle-launch-smoke" / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output.mkdir(parents=True)
    marker = output / "stop.request"
    agent = f"http://127.0.0.1:{args.base_port}"
    children, logs = [], []

    def start(check_only=False):
        name = "check-only" if check_only else "launcher"
        log = (output / (name + ".log")).open("wb"); logs.append(log)
        if os.name == "nt":
            command = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(bundle / "start-demo.ps1"),
                "-BasePort", str(args.base_port), *(["-CheckOnly"] if check_only else ["-StopFile", str(marker)])]
        else:
            command = ["bash", str(bundle / "start-demo.sh"), str(args.base_port)]
        child = subprocess.Popen(command, cwd=bundle, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        children.append(child)
        return child

    def stopped_ports():
        for offset in offsets:
            with socket.socket() as probe:
                probe.settimeout(1)
                assert probe.connect_ex(("127.0.0.1", args.base_port + offset)) != 0, "A package process did not stop"

    def stop(child):
        if child.poll() is not None:
            return
        if os.name == "nt":
            marker.write_text("stop\n", encoding="utf-8")
        else:
            child.terminate()  # The Bash launcher handles TERM and cleans up its children.
        try:
            child.wait(timeout=45)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(timeout=5)
            raise TimeoutError(f"Launcher did not stop; see {output}")

    try:
        if os.name == "nt":
            check = start(check_only=True)
            assert check.wait(timeout=120) == 0, f"Windows CheckOnly failed; see {output}"
            stopped_ports()
        launcher = start()
        deadline = time.monotonic() + 100
        while True:
            assert launcher.poll() is None, f"Launcher stopped during startup; see {output}"
            try:
                configs = [request(agent, "/api/config?service=" + service)[1] for service in
                    ("order-service", "account-service", "catalog-service", "catalog-db-service", "ticket-service")]
                if all(value["mode"] == "DEMO" and value["observationAvailable"] for value in configs):
                    break
            except (OSError, ValueError, KeyError):
                pass
            if time.monotonic() > deadline:
                raise TimeoutError(f"Package startup timed out; see {output}")
            time.sleep(.5)

        def verify(script, *options):
            subprocess.run([sys.executable, str(ROOT / "scripts" / script), *map(str, options)], cwd=ROOT, check=True)

        verify("live_smoke.py", "--agent-url", agent, "--sample-url", f"http://127.0.0.1:{args.base_port + 2}",
            "--inventory-url", f"http://127.0.0.1:{args.base_port + 4}")
        verify("db_pool_smoke.py", "--agent-url", agent, "--database-url", f"http://127.0.0.1:{args.base_port + 6}")
        verify("bundle_services_smoke.py", "--base-port", args.base_port, "--bundle-directory", bundle)
        verify("workspace_smoke.py", "--agent-url", agent)
        stop(launcher)
        assert launcher.returncode == (0 if os.name == "nt" else 143), "Launcher did not follow its normal stop path"
        stopped_ports()
        assert (bundle / "data/triage.mv.db").is_file(), "Package history was not retained"
        print("Package launcher passed: eight processes, five services, HTTP/JDBC/V3, source and history; owned processes stopped and data retained")
    finally:
        for child in reversed(children):
            stop(child)
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
