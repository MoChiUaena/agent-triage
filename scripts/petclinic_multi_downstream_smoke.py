#!/usr/bin/env python3
"""Verify V5 against a pinned Spring Petclinic REST checkout and two loopback HTTP services."""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
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
from petclinic_memory_profile import capture as capture_memory, write_bundle as write_memory_bundle
from petclinic_multi_cost import measure, request_writer, write_samples


def complete_window(application, minutes, expected_count):
    deadline = time.monotonic() + 5
    last_status, last_count = None, None
    while time.monotonic() < deadline:
        query = urlencode({"windowMinutes": minutes,
            "endTime": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")})
        status, observed = request(application, "/petclinic/triage/endpoint-observations?" + query)
        last_status, last_count = status, observed.get("requestCount") if isinstance(observed, dict) else None
        if status == 200 and observed.get("schemaVersion") == 5 and last_count == expected_count:
            return observed
        if status != 200 or last_count is not None and last_count > expected_count:
            break
        time.sleep(.1)
    raise ValueError(f"V5 window mismatch: HTTP {last_status}, requests {last_count}, expected {expected_count}")


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
        try:
            self.wfile.write(b"{}")
        except OSError:
            pass  # A timed-out client has already closed its connection.

    def log_message(self, format, *args):
        pass


def memory_profile_hook(path, pid, seconds, jcmd, origin):
    state = {"thread": None, "before": None, "failure": None}

    def on_sample(values):
        write_samples(path, values)
        if state["thread"] is None and values[-1]["elapsedSeconds"] >= seconds / 5:
            def collect():
                try:
                    state["before"] = capture_memory(pid, "before", jcmd, origin)
                except Exception as error:
                    state["failure"] = error
            state["thread"] = threading.Thread(target=collect, daemon=True)
            state["thread"].start()

    def finish():
        if state["thread"] is None:
            raise ValueError("Missing warmup-aligned memory profile sample")
        state["thread"].join(timeout=25)
        if state["thread"].is_alive():
            raise TimeoutError("Memory profile sampling did not finish")
        if state["failure"] is not None:
            raise ValueError("Memory profile sampling failed") from state["failure"]
        return [state["before"], capture_memory(pid, "after", jcmd, origin)]

    return on_sample, finish


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-directory", required=True, type=Path)
    parser.add_argument("--base-port", type=int, default=18720)
    parser.add_argument("--load-seconds", type=int, default=0,
                        help="Opt-in 60..3600 second, two-request-per-second process-memory baseline")
    parser.add_argument("--compare-starter-off", action="store_true",
                        help="Run the same workload in a second JVM with Starter disabled on this host")
    parser.add_argument("--memory-profile", action="store_true",
                        help="Also capture opt-in numeric JVM/Linux memory phases outside the request schedule")
    args = parser.parse_args()
    if args.load_seconds != 0 and not 60 <= args.load_seconds <= 3600:
        parser.error("load-seconds must be 0 or 60..3600")
    if args.compare_starter_off and not args.load_seconds:
        parser.error("compare-starter-off requires load-seconds")
    if args.memory_profile and (os.name == "nt" or not args.compare_starter_off):
        parser.error("memory-profile requires Linux and a Starter-off control")
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
    jcmd = Path(os.environ["JAVA_HOME"]) / "bin/jcmd" if args.memory_profile else None
    if args.memory_profile and not jcmd.is_file():
        parser.error("memory-profile requires jcmd in JAVA_HOME/bin")
    profile_options = ("-XX:NativeMemoryTracking=summary",) if args.memory_profile else ()
    children, handles, servers, threads = [], [], [], []

    def start(name, jar, options, jvm_options=()):
        assert jar.is_file(), f"Build {name} first"
        runtime = output / (name + ".jar")
        shutil.copyfile(jar, runtime)
        stream = (output / (name + ".log")).open("wb")
        handles.append(stream)
        children.append(subprocess.Popen([java, *jvm_options, "-jar", str(runtime), *options], cwd=ROOT,
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
            f"--triage.verification.payment-base-url={payment}"], jvm_options=profile_options)
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
            if status != 200 or body != {"inventory": "timeout", "payment": result}:
                raise ValueError(f"Functional public request mismatch: status={status}, paymentMode={fail}; see {output}")

        observed = complete_window(application, 5, 2)
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
        if args.load_seconds:
            cost_started = time.monotonic()
            enabled_sample, finish_enabled_profile = memory_profile_hook(
                output / "cost.csv", children[0].pid, args.load_seconds, jcmd, cost_started) \
                if args.memory_profile else (lambda values: write_samples(output / "cost.csv", values), None)
            def invoke(index):
                assert all(child.poll() is None for child in children), "An owned JVM stopped during the cost probe"
                fail = index % 2 == 1
                try:
                    status, body = request(application, "/petclinic/api/triage-multi/check?failPayment="
                        + str(fail).lower(), headers=headers)
                except (OSError, ValueError) as error:
                    (output / "cost-failure.json").write_text(json.dumps({"completedBeforeFailure": index,
                        "elapsedSeconds": round(time.monotonic() - cost_started, 3), "status": None,
                        "paymentExpected5xx": fail, "failureType": type(error).__name__}, indent=2), encoding="utf-8")
                    raise ValueError(f"Cost request failed at request {index}; see numeric receipt in {output}") from None
                if status != 200 or body != {"inventory": "timeout", "payment": "serverError" if fail else "ok"}:
                    (output / "cost-failure.json").write_text(json.dumps({"completedBeforeFailure": index,
                        "elapsedSeconds": round(time.monotonic() - cost_started, 3), "status": status,
                        "paymentExpected5xx": fail}, indent=2), encoding="utf-8")
                    raise ValueError(f"Cost request mismatch at request {index}, HTTP {status}; see numeric receipt in {output}")

            with (output / "requests.csv").open("w", newline="", encoding="utf-8") as timing:
                cost, samples = measure(args.load_seconds, children[0].pid, invoke,
                    on_sample=enabled_sample,
                    on_request=request_writer(timing))
            if args.memory_profile:
                enabled_profile = finish_enabled_profile()
            expected = 2 + cost["completedRequests"]
            if args.load_seconds > 600:
                status, observed = request(application, "/petclinic/triage/endpoint-observations?" + urlencode({
                    "windowMinutes": 15, "endTime": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")}))
                assert status == 200 and observed["schemaVersion"] == 5
                assert observed["recordedRequestCount"] == expected
                assert 1770 <= observed["requestCount"] <= 1802
            else:
                observed = complete_window(application, 15, expected)
            rows = {row["id"]: row for row in observed["downstreams"]}
            window_count = observed["requestCount"]
            assert rows["inventory-service"]["timeoutCount"] == window_count
            assert rows["inventory-service"]["responseStatuses"]["unknown"] == window_count
            assert rows["payment-service"]["requestCount"] == window_count
            assert rows["payment-service"]["timeoutCount"] == 0
            successful = rows["payment-service"]["responseStatuses"]["successful"]
            server_error = rows["payment-service"]["responseStatuses"]["serverError"]
            if args.load_seconds > 600:
                assert successful + server_error == window_count and abs(successful - server_error) <= 1
            else:
                assert successful == 1 + (cost["completedRequests"] + 1) // 2
                assert server_error == 1 + cost["completedRequests"] // 2
            write_samples(output / "cost.csv", samples)
            source_commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            cost.update(sourceCommit=source_commit, upstreamRevision=REVISION, observedRequests=window_count,
                        inventoryTimeouts=rows["inventory-service"]["timeoutCount"],
                        payment2xx=successful, payment5xx=server_error, agentValidated=False,
                        artifactVersion=3 if args.load_seconds > 600 else 2,
                        requestsCsvSha256=hashlib.sha256((output / "requests.csv").read_bytes()).hexdigest(),
                        memoryCsvSha256=hashlib.sha256((output / "cost.csv").read_bytes()).hexdigest())
            if args.load_seconds > 600:
                cost.update(recordedRequests=observed["recordedRequestCount"], observationWindowMinutes=15,
                            agentWindowRequests=0)
            cost_path = output / "cost-summary.json"
            cost_path.write_text(json.dumps(cost, ensure_ascii=False, indent=2), encoding="utf-8")

            status, cost_run = request(agent, "/api/runs", {"question": "Petclinic REST 多下游请求超时和响应分类有哪些证据？",
                "service": "petclinic-rest-service", "windowMinutes": 15})
            assert status == 202, (status, cost_run)
            deadline = time.monotonic() + 20
            while cost_run["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
                time.sleep(.1)
                _, cost_run = request(agent, "/api/runs/" + cost_run["id"])
            assert cost_run["status"] == "SUCCEEDED" and valid_citations(cost_run), (cost_run["status"], cost_run.get("failure"))
            cost_metrics = evidence(cost_run, "read_service_metrics")["data"]
            agent_count = cost_metrics["requestCount"]
            if args.load_seconds > 600:
                assert 0 <= window_count - agent_count <= 60
                assert {row["id"]: row for row in cost_metrics["downstreams"]}["inventory-service"]["timeoutCount"] == agent_count
                cost["agentWindowRequests"] = agent_count
            else:
                assert agent_count == expected
                assert {row["id"]: row for row in cost_metrics["downstreams"]}["payment-service"]["responseStatuses"]["serverError"] \
                    == server_error
            cost["agentValidated"] = True
            cost_path.write_text(json.dumps(cost, ensure_ascii=False, indent=2), encoding="utf-8")
            if args.memory_profile:
                write_memory_bundle(output, enabled_profile, source_commit, REVISION, args.load_seconds,
                                    cost_path, True)
            print(f"Petclinic REST V5 cost baseline: {cost['completedRequests']} requests in {args.load_seconds}s, "
                  f"p95 {cost['requestP95Ms']}ms, RSS peak growth {cost['rssPeakGrowthBytes']} bytes")
            if args.compare_starter_off:
                children[0].terminate()
                try:
                    children[0].wait(timeout=15)
                except subprocess.TimeoutExpired:
                    children[0].kill()
                    children[0].wait(timeout=5)
                start("petclinic-control", public / "target/spring-petclinic-rest-3.4.0.jar", [
                    f"--server.port={args.base_port + 1}", "--server.address=127.0.0.1",
                    "--triage.sdk.enabled=false", "--triage.verification.multi-enabled=true",
                    f"--triage.verification.inventory-base-url={inventory}",
                    f"--triage.verification.payment-base-url={payment}"], jvm_options=profile_options)
                control_process = children[-1]
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    assert control_process.poll() is None and children[1].poll() is None, "A control JVM stopped"
                    try:
                        if request(application, "/petclinic/actuator/health")[0] == 200:
                            break
                    except (OSError, ValueError):
                        pass
                    time.sleep(.5)
                else:
                    raise TimeoutError(f"Starter-off Petclinic startup timed out; see {output}")
                endpoint_status, endpoint_error = request(application, "/petclinic/triage/endpoint-observations")
                assert endpoint_status == 500 and endpoint_error.get("title") == "NoResourceFoundException" \
                    and endpoint_error.get("detail") == "No static resource triage/endpoint-observations.", \
                    "Starter-off control still exposes observations or returned an unexpected error"
                for fail, result in (("false", "ok"), ("true", "serverError")):
                    status, body = request(application, "/petclinic/api/triage-multi/check?failPayment=" + fail,
                                           headers=headers)
                    if status != 200 or body != {"inventory": "timeout", "payment": result}:
                        raise ValueError(f"Starter-off functional request mismatch: {status}, {fail}")

                control_started = time.monotonic()
                control_sample, finish_control_profile = memory_profile_hook(
                    output / "control-cost.csv", control_process.pid, args.load_seconds, jcmd, control_started) \
                    if args.memory_profile else (lambda values: write_samples(output / "control-cost.csv", values), None)
                def control_invoke(index):
                    assert control_process.poll() is None and children[1].poll() is None, "A control JVM stopped"
                    fail = index % 2 == 1
                    try:
                        status, body = request(application, "/petclinic/api/triage-multi/check?failPayment="
                            + str(fail).lower(), headers=headers)
                    except (OSError, ValueError) as error:
                        (output / "control-failure.json").write_text(json.dumps({"completedBeforeFailure": index,
                            "elapsedSeconds": round(time.monotonic() - control_started, 3),
                            "status": None, "paymentExpected5xx": fail,
                            "failureType": type(error).__name__}, indent=2), encoding="utf-8")
                        raise ValueError(f"Control request failed at {index}; see {output}") from None
                    if status != 200 or body != {"inventory": "timeout", "payment": "serverError" if fail else "ok"}:
                        (output / "control-failure.json").write_text(json.dumps({"completedBeforeFailure": index,
                            "elapsedSeconds": round(time.monotonic() - control_started, 3),
                            "status": status, "paymentExpected5xx": fail}, indent=2), encoding="utf-8")
                        raise ValueError(f"Control request mismatch at {index}; see {output}")

                with (output / "control-requests.csv").open("w", newline="", encoding="utf-8") as timing:
                    control_cost, control_samples = measure(args.load_seconds, control_process.pid, control_invoke,
                        on_sample=control_sample,
                        on_request=request_writer(timing))
                if args.memory_profile:
                    control_profile = finish_control_profile()
                write_samples(output / "control-cost.csv", control_samples)
                control_cost.update(sourceCommit=source_commit, upstreamRevision=REVISION,
                    starterEnabled=False, functionalRequests=2, artifactVersion=2,
                    requestsCsvSha256=hashlib.sha256((output / "control-requests.csv").read_bytes()).hexdigest(),
                    memoryCsvSha256=hashlib.sha256((output / "control-cost.csv").read_bytes()).hexdigest())
                control_path = output / "control-summary.json"
                control_path.write_text(json.dumps(control_cost, indent=2), encoding="utf-8")
                if args.memory_profile:
                    write_memory_bundle(output, control_profile, source_commit, REVISION, args.load_seconds,
                                        control_path, False)
                print(f"Petclinic REST Starter-off control: {control_cost['completedRequests']} requests in "
                      f"{args.load_seconds}s, p95 {control_cost['requestP95Ms']}ms, "
                      f"RSS peak growth {control_cost['rssPeakGrowthBytes']} bytes")
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
