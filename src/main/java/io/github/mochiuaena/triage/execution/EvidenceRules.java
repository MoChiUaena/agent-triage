package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import java.util.*;

public final class EvidenceRules {
    public static final String DB_TIMEOUT = "DOC-DB-POOL-EXHAUSTION#";
    public static final String DB_BASELINE = "DOC-DB-POOL-BASELINE#";
    public static final String DB_SQL_FAILURE = "DOC-DB-SQL-EXECUTION-FAILURE#";
    private EvidenceRules() {}
    public static boolean database(Evidence metrics) { return metrics != null && "DATABASE_POOL".equals(metrics.data().get("observationType")); }
    public static Map<?, ?> pool(Evidence metrics) {
        return metrics != null && metrics.data().get("databasePool") instanceof Map<?, ?> values ? values : Map.of();
    }
    public static long count(Map<?, ?> data, String key) { return data.get(key) instanceof Number number ? number.longValue() : -1; }
    public static String required(Evidence metrics) {
        if (database(metrics)) {
            var values = pool(metrics);
            return count(values, "acquisitionTimeoutCount") > 0 ? DB_TIMEOUT
                : count(values, "queryErrorCount") > 0 ? DB_SQL_FAILURE : DB_BASELINE;
        }
        return metrics != null && metrics.data().get("downstreamTimeoutRate") instanceof Number rate
            ? rate.doubleValue() > 0 ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#" : null;
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
