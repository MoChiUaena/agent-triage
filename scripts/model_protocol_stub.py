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
        if isinstance(values, dict) and "error" in values:
            continue
        if not isinstance(values, list):
            raise ValueError("Tool result is not a list")
        evidence.extend(values)
    return evidence


def correction_requested(messages):
    last_assistant = max((index for index, message in enumerate(messages) if message.get("role") == "assistant"), default=-1)
    return any(isinstance(value := json.loads(message["content"]), dict) and "error" in value
               for message in messages[last_assistant + 1:] if message.get("role") == "tool")


def arguments_for(request):
    properties = request["tools"][0]["function"]["parameters"]["properties"]
    return {"service": properties["service"]["const"], "windowMinutes": properties["windowMinutes"]["const"]}


def calls_for(messages, arguments, corrected=False, invalid=False, omit_rules=False):
    user = next((message.get("content", "") for message in messages if message.get("role") == "user"), "")
    query = "延迟" if "接口延迟原因" in str(user) else "订单 超时 正常"
    prefix = "corrected-" if corrected else ""
    calls = [
        tool_call(prefix + "docs", "search_runbooks", {**arguments, "query": query}),
        tool_call(prefix + "metrics", "read_service_metrics", {**arguments, "windowMinutes": str(arguments["windowMinutes"]) if invalid else arguments["windowMinutes"]}),
        tool_call(prefix + "logs", "query_error_logs", arguments),
    ]
    return calls[1:] if omit_rules else calls


def final_answer(evidence):
    metrics = next(item for item in evidence if item["source"] == "read_service_metrics")
    logs = next(item for item in evidence if item["source"] == "query_error_logs")
    count = metrics["data"]["requestCount"]
    if count == 0:
        return {"assessment": "INSUFFICIENT_EVIDENCE", "evidenceIds": [], "nextChecks": ["COLLECT_OBSERVATIONS"]}
    if metrics["data"].get("observationType") == "DATABASE_POOL":
        pool = metrics["data"]["databasePool"]
        exhausted = pool["acquisitionTimeoutCount"] > 0 and pool["peakActiveConnections"] == pool["maximumConnections"] and pool["exhaustedSamples"] > 0
        normal = pool["acquisitionTimeoutCount"] == 0 and pool["acquisitionErrorCount"] == 0 and pool["queryErrorCount"] == 0 and not logs["data"]["entries"]
        sql_failed = pool["acquisitionTimeoutCount"] == 0 and pool["acquisitionErrorCount"] == 0 and pool["queryErrorCount"] > 0 \
            and any(item["code"] == "SQL_QUERY_FAILED" for item in logs["data"]["entries"])
        assessment = "DB_POOL_EXHAUSTION_OBSERVED" if exhausted else "DB_SQL_EXECUTION_FAILURE_OBSERVED" if sql_failed \
            else "NO_DB_POOL_EXHAUSTION_OBSERVED" if normal else "INSUFFICIENT_EVIDENCE"
        prefix = "DOC-DB-POOL-EXHAUSTION#" if exhausted else "DOC-DB-SQL-EXECUTION-FAILURE#" if sql_failed else "DOC-DB-POOL-BASELINE#"
        rule = next(item for item in evidence if item["id"].startswith(prefix))
        return {"assessment": assessment, "evidenceIds": [metrics["id"], logs["id"], rule["id"]],
                "nextChecks": ["INSPECT_DB_CONNECTION_HOLDERS", "VERIFY_DB_POOL_LIMITS"] if exhausted
                    else ["INSPECT_DB_QUERIES", "CORRELATE_TRACE"] if sql_failed else ["INSPECT_DB_QUERIES", "COLLECT_RESOURCE_METRICS"]}
    timed_out = metrics["data"]["downstreamTimeoutRate"] > 0
    doc_prefix = "DOC-DOWNSTREAM-TIMEOUT#" if timed_out else "DOC-HEALTHY-BASELINE#"
    rule = next(item for item in evidence if item["id"].startswith(doc_prefix))
    return {"assessment": "DOWNSTREAM_TIMEOUT_OBSERVED" if timed_out else "NO_DOWNSTREAM_TIMEOUT_OBSERVED",
            "evidenceIds": [metrics["id"], logs["id"], rule["id"]],
            "nextChecks": ["INSPECT_INVENTORY_LATENCY", "CORRELATE_TRACE"] if timed_out
                          else ["FIND_SLOW_REQUEST", "COLLECT_RESOURCE_METRICS"]}


class Handler(BaseHTTPRequestHandler):
    invalid_first_arguments = False
    omit_rules_first = False
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
            elif has_tools and correction_requested(messages):
                payload = response({"role": "assistant", "content": None, "tool_calls": calls_for(messages, arguments_for(request), corrected=True)}, "tool_calls")
            elif has_tools:
                evidence = tool_results(messages)
                has_rules = any(item["source"] == "search_runbooks" for item in evidence)
                last_assistant = max(index for index, message in enumerate(messages) if message.get("role") == "assistant")
                feedback = any(message.get("role") == "system" and "EVIDENCE_FEEDBACK" in str(message.get("content", ""))
                               for message in messages[last_assistant + 1:])
                if self.omit_rules_first and not has_rules and feedback:
                    calls = [tool_call("required-docs", "search_runbooks", {**arguments_for(request), "query": "正常 超时"})]
                    payload = response({"role": "assistant", "content": None, "tool_calls": calls}, "tool_calls")
                else:
                    if self.omit_rules_first and not has_rules:
                        metrics = next(item for item in evidence if item["source"] == "read_service_metrics")
                        answer = {"assessment": "DOWNSTREAM_TIMEOUT_OBSERVED" if metrics["data"]["downstreamTimeoutRate"] > 0 else "NO_DOWNSTREAM_TIMEOUT_OBSERVED",
                                  "evidenceIds": [item["id"] for item in evidence], "nextChecks": ["COLLECT_RESOURCE_METRICS"]}
                    else:
                        answer = final_answer(evidence)
                    payload = response({"role": "assistant", "content": json.dumps(answer, ensure_ascii=False)}, "stop")
            else:
                tools = json.dumps(request.get("tools", []), ensure_ascii=False)
                if "实际请求" not in tools or "合成观测" in tools:
                    raise ValueError("LIVE tool descriptions are missing")
                calls = calls_for(messages, arguments_for(request), invalid=self.invalid_first_arguments, omit_rules=self.omit_rules_first)
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
    parser.add_argument("--invalid-first-arguments", action="store_true", help="Inject a string window, then correct it after application feedback")
    parser.add_argument("--omit-rules-first", action="store_true", help="Return a premature final answer, then collect the missing rule after feedback")
    args = parser.parse_args()
    Handler.invalid_first_arguments = args.invalid_first_arguments
    Handler.omit_rules_first = args.omit_rules_first
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Protocol stub listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
