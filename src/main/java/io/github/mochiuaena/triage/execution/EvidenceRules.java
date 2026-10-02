package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import java.util.*;

public final class EvidenceRules {
    public static final String DB_TIMEOUT = "DOC-DB-POOL-EXHAUSTION#";
    public static final String DB_BASELINE = "DOC-DB-POOL-BASELINE#";
    public static final String DB_SQL_FAILURE = "DOC-DB-SQL-EXECUTION-FAILURE#";
    public static final String REQUEST_EXECUTION = "DOC-REQUEST-EXECUTION-FAILURE#";
    private EvidenceRules() {}
    public static boolean database(Evidence metrics) { return metrics != null && "DATABASE_POOL".equals(metrics.data().get("observationType")); }
    public static boolean inbound(Evidence metrics) { return metrics != null && "HTTP_REQUESTS".equals(metrics.data().get("observationType")); }
    public static Map<?, ?> pool(Evidence metrics) {
        return metrics != null && metrics.data().get("databasePool") instanceof Map<?, ?> values ? values : Map.of();
    }
    public static long count(Map<?, ?> data, String key) { return data.get(key) instanceof Number number ? number.longValue() : -1; }
    public static boolean responseStatusGap(Evidence metrics) {
        return metrics != null && metrics.data().get("responseStatuses") instanceof Map<?, ?> values
            && (count(values, "clientError") > 0 || count(values, "serverError") > 0 || count(values, "unknown") > 0);
    }
    public static String required(Evidence metrics) {
        if (inbound(metrics)) return count(requestFailures(metrics), "executionFailures") > 0 ? REQUEST_EXECUTION : "DOC-HTTP-REQUESTS-BOUNDARY#";
        if (database(metrics)) {
            var values = pool(metrics);
            return count(values, "acquisitionTimeoutCount") > 0 ? DB_TIMEOUT
                : count(values, "queryErrorCount") > 0 ? DB_SQL_FAILURE : DB_BASELINE;
        }
        return metrics != null && metrics.data().get("downstreamTimeoutRate") instanceof Number rate
            ? rate.doubleValue() > 0 ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#" : null;
    }
    public static Map<?, ?> requestFailures(Evidence evidence) {
        return evidence != null && evidence.data().get("requestFailures") instanceof Map<?, ?> values ? values : Map.of();
    }
    public static boolean requestExecutionFailed(Evidence metrics, Evidence logs) {
        if (!inbound(metrics) || !inbound(logs) || count(metrics.data(), "requestCount") < 1
            || count(logs.data(), "requestCount") != count(metrics.data(), "requestCount")) return false;
        for (String key : List.of("service", "windowStart", "windowEnd"))
            if (metrics.data().get(key) == null || !Objects.equals(metrics.data().get(key), logs.data().get(key))) return false;
        var failures = requestFailures(metrics);
        if (count(failures, "executionFailures") < 1 || count(failures, "executionFailures") > count(metrics.data(), "requestCount")) return false;
        for (String key : List.of("executionFailures", "serverErrorResponses", "asyncTimeouts", "asyncErrors", "handledExceptions"))
            if (count(failures, key) < 0 || count(failures, key) != count(requestFailures(logs), key)) return false;
        if (!(metrics.data().get("responseStatuses") instanceof Map<?, ?> statuses)
            || count(statuses, "serverError") < 0 || count(statuses, "unknown") < 0
            || count(failures, "executionFailures") > count(statuses, "serverError") + count(statuses, "unknown")) return false;
        if (!(logs.data().get("entries") instanceof List<?> entries) || count(logs.data(), "returnedCount") != entries.size()) return false;
        return entries.stream().anyMatch(item -> item instanceof Map<?, ?> entry && "REQUEST_EXECUTION_FAILED".equals(entry.get("code"))
            && "ERROR".equals(entry.get("level")) && (count(entry, "responseClass") == 0 || count(entry, "responseClass") == 5)
            && eventInWindow(entry, metrics));
    }
    private static boolean eventInWindow(Map<?, ?> entry, Evidence metrics) {
        try {
            var time = java.time.Instant.parse(String.valueOf(entry.get("timestamp")));
            return !time.isBefore(java.time.Instant.parse(String.valueOf(metrics.data().get("windowStart"))))
                && !time.isAfter(java.time.Instant.parse(String.valueOf(metrics.data().get("windowEnd"))));
        } catch (RuntimeException invalid) { return false; }
    }
    public static boolean exhausted(Evidence metrics, Evidence logs) {
        var pool = pool(metrics);
        if (count(pool, "maximumConnections") < 1 || count(pool, "poolSamples") < 1
            || count(pool, "peakActiveConnections") != count(pool, "maximumConnections")
            || count(pool, "peakPendingThreads") < 1 || count(pool, "exhaustedSamples") < 1 || count(pool, "acquisitionTimeoutCount") < 1
            || !sameDatabaseCounters(metrics, logs)) return false;
        return logs.data().get("entries") instanceof List<?> entries && entries.stream().anyMatch(entry -> entry instanceof Map<?, ?> data
            && "DB_CONNECTION_ACQUIRE_TIMEOUT".equals(data.get("code")));
    }
    public static boolean noDatabaseTimeout(Evidence metrics, Evidence logs) {
        var pool = pool(metrics);
        return count(pool, "poolSamples") > 0 && count(pool, "acquisitionTimeoutCount") == 0 && count(pool, "acquisitionErrorCount") == 0
            && count(pool, "queryErrorCount") == 0 && sameDatabaseCounters(metrics, logs)
            && logs.data().get("entries") instanceof List<?> entries && entries.isEmpty();
    }
    public static boolean sqlExecutionFailed(Evidence metrics, Evidence logs) {
        var values = pool(metrics);
        if (count(metrics.data(), "requestCount") < 1 || count(values, "queryCount") < 1
            || count(values, "queryErrorCount") < 1 || count(values, "queryErrorCount") > count(values, "queryCount")
            || count(values, "acquisitionTimeoutCount") != 0
            || count(values, "acquisitionErrorCount") != 0 || !sameDatabaseCounters(metrics, logs)) return false;
        return logs.data().get("entries") instanceof List<?> entries
            && count(logs.data(), "returnedCount") == entries.size()
            && entries.stream().anyMatch(entry -> entry instanceof Map<?, ?> data
                && "SQL_QUERY_FAILED".equals(data.get("code")));
    }
    private static boolean sameDatabaseCounters(Evidence metrics, Evidence logs) {
        if (logs == null || count(logs.data(), "requestCount") != count(metrics.data(), "requestCount")) return false;
        return List.of("acquisitionTimeoutCount", "acquisitionErrorCount", "queryErrorCount").stream()
            .allMatch(key -> count(logs.data(), key) == count(pool(metrics), key));
    }
}
