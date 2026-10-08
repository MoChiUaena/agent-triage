"""Numeric contract for the bounded Servlet/downstream acceptance workload."""
import re

HTTP_COMPONENTS = ("http-disabled", "http-enabled")
NATIVE_BREAKDOWN = {"native" + name + "CommittedBytes" for name in
    ("Class", "Thread", "Code", "GC", "Other", "Uncategorized")}


def parse_http_receipt(receipt, component, seconds):
    tokens = receipt.split()
    if any(re.fullmatch(r"[A-Za-z]+=(?:[0-9]+|true|false)", token) is None for token in tokens):
        raise ValueError("Invalid HTTP workload receipt token")
    fields = dict(token.split("=") for token in tokens)
    required = {"seconds", "cycles", "rps", "concurrency", "requests", "successes", "failures",
        "typedTimeouts", "untypedFailures", "observedRequests", "observedTimeouts",
        "expectedWindowRequests", "expectedWindowTimeouts", "windowRequests", "windowTimeouts",
        "responsesOpened", "responsesClosed", "downstreamRequests", "servletContexts", "workerContexts",
        "downstreamActive", "queues", "payloadErrors", "executorsStopped", "servletStopped",
        "maxLagMillis", "baselineHeap", "peakHeap", "finalHeap"}
    profile = {"connectionPolicy", "healthyConnectionsReused", "faultConnectionsClosed"}
    profiled = bool(set(fields) & profile)
    jdk_classification = {"jdkTypedTimeouts"} if "jdkTypedTimeouts" in fields else set()
    if len(tokens) != len(fields) or set(fields) != required | (profile if profiled else set()) | jdk_classification:
        raise ValueError("Missing or duplicate HTTP workload counters")
    value = {key: raw == "true" if raw in ("true", "false") else int(raw) for key, raw in fields.items()}
    booleans = {"executorsStopped", "servletStopped", "healthyConnectionsReused", "faultConnectionsClosed"}
    if any(type(number) is not (bool if key in booleans else int) for key, number in value.items()):
        raise ValueError("Wrong HTTP counter type")
    if profiled and (value["connectionPolicy"] != 2 or value["healthyConnectionsReused"] is not True or value["faultConnectionsClosed"] is not True):
        raise ValueError("Fault-close profile lacks verified healthy reuse or connection cleanup")
    enabled = component == "http-enabled"
    jdk_typed = value.get("jdkTypedTimeouts", 0)
    if not 0 <= jdk_typed <= seconds:
        raise ValueError("JDK typed failure count exceeds its request count")
    expected = dict(seconds=seconds, cycles=seconds, rps=8, concurrency=4, requests=seconds*8,
        successes=seconds*3, failures=seconds*5, typedTimeouts=seconds*4+jdk_typed, untypedFailures=seconds-jdk_typed,
        observedRequests=seconds*8 if enabled else 0, observedTimeouts=seconds*4+jdk_typed if enabled else 0,
        responsesOpened=seconds*7, responsesClosed=seconds*7, downstreamRequests=seconds*8,
        servletContexts=0, workerContexts=0, downstreamActive=0, queues=0, payloadErrors=0,
        executorsStopped=True, servletStopped=True)
    if any(value[key] != number for key, number in expected.items()):
        raise ValueError("Incomplete HTTP workload, observations or cleanup")
    expected_requests = value["expectedWindowRequests"]
    if not min(seconds*8, 480)-16 <= expected_requests <= min(seconds*8, 484):
        raise ValueError("Final HTTP window lacks completed traffic")
    expected_timeouts = value["expectedWindowTimeouts"]
    if seconds <= 30:
        if expected_requests != seconds*8 or expected_timeouts != value["typedTimeouts"]:
            raise ValueError("Short HTTP window must contain the full workload")
    elif not min(seconds, 60)*4-16 <= expected_timeouts <= min(value["typedTimeouts"], expected_requests, 308):
        raise ValueError("Final HTTP timeout window lacks the fixed-rate workload")
    if value["windowRequests"] != (expected_requests if enabled else 0) or value["windowTimeouts"] != (value["expectedWindowTimeouts"] if enabled else 0):
        raise ValueError("Final HTTP window differs from completed requests")
    if value["maxLagMillis"] > 2000:
        raise ValueError("HTTP driver failed to sustain requested rate")
    if max(value["peakHeap"], value["finalHeap"]) > value["baselineHeap"] + 64*1024*1024:
        raise ValueError("Retained HTTP heap exceeds the workload gate")
    return value
