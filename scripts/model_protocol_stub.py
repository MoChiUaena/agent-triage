#!/usr/bin/env python3
"""Local OpenAI-compatible protocol stub for LIVE integration tests, not an AI model."""

import argparse
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def response(message, finish_reason):
    return {
        "id": "live-protocol-stub", "created": 1, "model": "triage-stub-v1",
        "choices": [{"index": 0, "finish_reason": finish_reason, "message": message}],
        "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150},
    }


def tool_call(id, name, arguments):
    return {"id": id, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments)}}


def tool_results(messages):
    evidence = []
    for message in messages:
        if message.get("role") != "tool":
            continue
        content = message.get("content", "")
        if isinstance(content, list):
            content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
        values = json.loads(content)
        if not isinstance(values, list):
            raise ValueError("Tool result is not a list")
        evidence.extend(values)
    return evidence


def final_answer(evidence):
    metrics = next(item for item in evidence if item["source"] == "read_service_metrics")
    logs = next(item for item in evidence if item["source"] == "query_error_logs")
    count = metrics["data"]["requestCount"]
    if count == 0:
        return {"status": "INSUFFICIENT_EVIDENCE", "diagnosis": {
            "observations": [], "possibleCauses": [],
            "nextSteps": ["先让订单服务处理一些请求，再重新排查。"],
            "uncertainty": "窗口里没有请求，无法判断当前状态。"}}
    timed_out = metrics["data"]["downstreamTimeoutRate"] > 0
    doc_prefix = "DOC-DOWNSTREAM-TIMEOUT#" if timed_out else "DOC-HEALTHY-BASELINE#"
    rule = next(item for item in evidence if item["id"].startswith(doc_prefix))
    cause = "库存调用超时可能拖慢订单查询。" if timed_out else "本次窗口未发现库存调用超时。"
    return {"status": "SUCCEEDED", "diagnosis": {
        "observations": [
            {"text": metrics["summary"], "evidenceIds": [metrics["id"]]},
            {"text": logs["summary"], "evidenceIds": [logs["id"]]},
        ],
        "possibleCauses": [{"text": cause, "evidenceIds": [metrics["id"], logs["id"], rule["id"]]}],
        "nextSteps": ["检查同一窗口内库存服务的处理耗时和 traceId。"],
        "uncertainty": "尚未采集网络、连接池和资源指标。",
    }}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/chat/completions":
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= 128_000:
            self.send_error(413)
            return
        try:
            request = json.loads(self.rfile.read(size))
            messages = request["messages"]
            has_tools = any(message.get("role") == "tool" for message in messages)
            if not request.get("tools"):
                user = next((message.get("content", "") for message in messages if message.get("role") == "user"), "")
                ids = list(dict.fromkeys(re.findall(r"DOC-[A-Z-]+#v\d+", str(user))))
                answer = {
                    "outcome": "INSUFFICIENT_EVIDENCE" if ids else "OUT_OF_SCOPE",
                    "answer": "只有排障文档，无法判断当前请求状态。" if ids else "该问题不在订单故障排查范围内。",
                    "citations": ids[:1],
                    "uncertainty": "缺少当前指标和错误日志。" if ids else "没有适用的订单排障文档。",
                }
                payload = response({"role": "assistant", "content": json.dumps(answer, ensure_ascii=False)}, "stop")
            elif has_tools:
                evidence = tool_results(messages)
                answer = json.dumps(final_answer(evidence), ensure_ascii=False)
                payload = response({"role": "assistant", "content": answer}, "stop")
            else:
                tools = json.dumps(request.get("tools", []), ensure_ascii=False)
                if "实际请求" not in tools or "合成观测" in tools:
                    raise ValueError("LIVE tool descriptions are missing")
                arguments = {"service": "order-service", "windowMinutes": 15}
                user = next((message.get("content", "") for message in messages if message.get("role") == "user"), "")
                query = "延迟" if "接口延迟原因" in str(user) else "订单 超时 正常"
                calls = [
                    tool_call("docs", "search_runbooks", {**arguments, "query": query}),
                    tool_call("metrics", "read_service_metrics", arguments),
                    tool_call("logs", "query_error_logs", arguments),
                ]
                payload = response({"role": "assistant", "content": None, "tool_calls": calls}, "tool_calls")
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
        except (KeyError, ValueError, TypeError, StopIteration, json.JSONDecodeError):
            body = b'{"error":"invalid local protocol test request"}'
            self.send_response(400)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18100)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Protocol stub listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
