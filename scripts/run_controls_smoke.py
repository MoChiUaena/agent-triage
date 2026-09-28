#!/usr/bin/env python3
"""Verify cancellation, usage coverage and provider failures with a local protocol fixture."""
import argparse
import json
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from live_smoke import request
from model_protocol_stub import response, calls_for, arguments_for, tool_results, final_answer


class Control:
    mode = "normal"
    release = threading.Event()
    lock = threading.Lock()
    active = 0


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/chat/completions":
            self.send_error(404); return
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= 128000:
            self.send_error(413); return
        data = json.loads(self.rfile.read(size))
        messages = data["messages"]
        final = any(message.get("role") == "tool" for message in messages)
        mode = Control.mode
        with Control.lock: Control.active += 1
        try:
            if mode == "delay_first" or mode == "delay_final" and final:
                Control.release.wait(15)
            if mode == "error" and final:
                status, payload = 503, {"error": "local fixture unavailable"}
            else:
                payload = response({"role": "assistant", "content": json.dumps(final_answer(tool_results(messages)), ensure_ascii=False)}, "stop") if final else response(
                    {"role": "assistant", "content": None, "tool_calls": calls_for(messages, arguments_for(data))}, "tool_calls")
                if mode == "partial" and not final: payload.pop("usage")
                status = 200
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            try:
                self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers()
                self.wfile.write(body)
            except ConnectionError: pass
        finally:
            with Control.lock: Control.active -= 1
    def log_message(self, *args): pass


def wait_for(action, seconds=12):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = action()
        if result: return result
        time.sleep(0.05)
    raise TimeoutError("Local execution did not reach the required state")


def mode(value):
    Control.release.set()
    wait_for(lambda: Control.active == 0)
    Control.mode = value; Control.release.clear()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent-url", default="http://127.0.0.1:18087")
    parser.add_argument("--stub-port", type=int, default=18102)
    parser.add_argument("--serve-only", action="store_true", help="Run only the local fixture for browser checks")
    parser.add_argument("--mode", choices=("normal", "partial", "error", "delay_first", "delay_final"), default="normal")
    args = parser.parse_args()
    agent = args.agent_url.rstrip("/")
    server = ThreadingHTTPServer(("127.0.0.1", args.stub_port), Handler)
    if args.serve_only:
        Control.mode = args.mode
        print("Local run-controls fixture ready", flush=True)
        server.serve_forever(); return
    threading.Thread(target=server.serve_forever, daemon=True).start()
    runs = []
    try:
        status, config = request(agent, "/api/config")
        assert status == 200 and config["mode"] == "MODEL" and config.get("model") == "controls-stub"
        assert request(agent, "/api/live-lab/traffic", {"scenario": "NORMAL", "count": 3}, {"X-Triage-Lab": "1"})[0] == 200
        def create():
            status, run = request(agent, "/api/runs", {"question": "订单请求为什么慢，请给出当前证据", "service": "order-service", "windowMinutes": 5,
                "scenario": "NORMAL", "expectedSelection": config["selectionToken"]})
            assert status == 202; runs.append(run); return run
        def read(run): return request(agent, "/api/runs/" + run["id"])[1]
        def completed(run):
            value = read(run); return value if value["status"] not in ("QUEUED", "RUNNING") else None
        def cancel(run):
            status, value = request(agent, "/api/runs/" + run["id"] + "/cancel", {}, {"X-Triage-Run": "1"})
            assert status == 200 and value["status"] == "CANCELLED"; return value
        for value in ("normal", "partial", "error"):
            mode(value); run = create()
            result = wait_for(lambda: completed(run)); model = result["modelExecution"]
            assert result["status"] == ("FAILED" if value == "error" else "SUCCEEDED")
            if value == "error": assert result["failure"]["code"] == "MODEL_HTTP_ERROR"
            assert model["knownUsage"]["totalTokens"] == (300 if value == "normal" else 150)
            assert model["usageReportedCalls"] == (2 if value == "normal" else 1)
            assert (model["usage"] is not None) == (value == "normal")
        mode("delay_final"); run = create()
        wait_for(lambda: read(run)["modelExecution"].get("completedCalls") == 1 and read(run)["modelExecution"]["calls"] == 2)
        assert request(agent, "/api/runs/" + run["id"] + "/cancel", {})[0] == 403
        cancelled = cancel(run)
        assert cancelled["modelExecution"]["knownUsage"]["totalTokens"] == 150 and cancelled["modelExecution"]["usage"] is None
        assert len(cancel(run)["events"]) == len(cancelled["events"])
        Control.release.set(); wait_for(lambda: Control.active == 0)
        assert read(run)["status"] == "CANCELLED" and len(read(run)["events"]) == len(cancelled["events"])
        mode("delay_first"); running = [create() for _ in range(4)]
        wait_for(lambda: all(read(run)["status"] == "RUNNING" for run in running))
        queued = create(); cancelled = cancel(queued)
        assert [event["type"] for event in cancelled["events"]] == ["RUN_QUEUED", "RUN_CANCELLED"]
        for run in running: cancel(run)
        Control.release.set(); wait_for(lambda: Control.active == 0)
        output = Path("target/run-controls") / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        output.mkdir(parents=True, exist_ok=False)
        for number, run in enumerate(runs):
            (output / (str(number) + ".json")).write_text(json.dumps(read(run), ensure_ascii=False, indent=2), encoding="utf-8")
        print("Run controls passed: complete and partial usage, provider failure, active/queued cancellation, idempotency and late-result fencing")
    finally:
        Control.release.set(); server.shutdown(); server.server_close()


if __name__ == "__main__": main()
