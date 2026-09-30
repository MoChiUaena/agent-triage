package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DatabaseModelOutputTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelOutput output = new ModelOutput(json);
    private final ServiceInfo info = new ServiceInfo("account-service", "账户服务", "accounts-db", "账户数据库");
    private List<Evidence> evidence(int requests, int timeouts, int queryErrors, int active, int pending, int overlap, String code) {
        var pool = new HashMap<String, Object>(Map.of("maximumConnections", 2, "peakActiveConnections", active, "peakPendingThreads", pending,
            "poolSamples", 20, "exhaustedSamples", overlap, "acquisitionTimeoutCount", timeouts, "acquisitionErrorCount", 0,
            "queryErrorCount", queryErrors, "acquisitionP95Ms", timeouts > 0 ? 351 : 1, "queryP95Ms", queryErrors > 0 ? 601 : 3));
        pool.put("queryCount", requests - timeouts);
        var entries = code == null ? List.of() : List.of(Map.of("code", code, "traceId", "fixture-trace", "message", "Database error"));
        return List.of(new Evidence(timeouts > 0 ? "DOC-DB-POOL-EXHAUSTION#v1" : queryErrors > 0
            ? "DOC-DB-SQL-EXECUTION-FAILURE#v1" : "DOC-DB-POOL-BASELINE#v1", "search_runbooks", "规则", "规则", Map.of()),
            new Evidence("METRICS-DB", "read_service_metrics", "指标", "实际数据库窗口指标", Map.of("observationType", "DATABASE_POOL", "requestCount", requests,
                "requestP95Ms", requests > 0 ? 351 : 0, "databasePool", pool)),
            new Evidence("LOGS-DB", "query_error_logs", "日志", "数据库错误事件", Map.of("observationType", "DATABASE_POOL", "requestCount", requests,
                "acquisitionTimeoutCount", timeouts, "acquisitionErrorCount", 0, "queryErrorCount", queryErrors, "entries", entries, "returnedCount", entries.size())));
    }
    private String answer(String assessment, List<Evidence> evidence, String... checks) throws Exception {
        return json.writeValueAsString(Map.of("assessment", assessment, "evidenceIds", evidence.stream().map(Evidence::id).toList(), "nextChecks", checks));
    }
    @Test void poolExhaustionRequiresBothWaitingSamplesAndTheCorrectFailureStage() throws Exception {
        var evidence = evidence(5, 1, 0, 2, 1, 5, "DB_CONNECTION_ACQUIRE_TIMEOUT");
        var result = output.parse(answer("DB_POOL_EXHAUSTION_OBSERVED", evidence, "INSPECT_DB_CONNECTION_HOLDERS", "VERIFY_DB_POOL_LIMITS"), evidence, info);
        assertThat(result.decision().status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.decision().diagnosis().possibleCauses().getFirst().text()).contains("连接获取超时", "等待线程").doesNotContain("连接泄漏", "数据库不可用");
        assertThat(result.decision().diagnosis().nextSteps()).hasSize(2);
        assertThat(new DemoReasoner().diagnose(evidence, info).possibleCauses()).hasSize(1);
    }
    @Test void normalObservationsDoNotProveOverallDatabaseHealth() throws Exception {
        var evidence = evidence(5, 0, 0, 1, 0, 0, null);
        var result = output.parse(answer("NO_DB_POOL_EXHAUSTION_OBSERVED", evidence, "INSPECT_DB_QUERIES"), evidence, info);
        assertThat(result.decision().diagnosis().possibleCauses().getFirst().text()).contains("本窗口未发现").doesNotContain("数据库健康", "服务健康");
    }
    @Test void sqlExecutionFailureIsASeparatePhaseNotAPoolDiagnosis() throws Exception {
        var evidence = evidence(5, 0, 1, 2, 0, 0, "SQL_QUERY_FAILED");
        var result = output.parse(answer("DB_SQL_EXECUTION_FAILURE_OBSERVED", evidence, "INSPECT_DB_QUERIES", "CORRELATE_TRACE"), evidence, info);
        assertThat(result.decision().status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.decision().diagnosis().possibleCauses().getFirst().text()).contains("SQL 执行阶段失败")
            .doesNotContain("锁等待导致", "连接池耗尽影响");
        assertThat(new DemoReasoner().diagnose(evidence, info).possibleCauses().getFirst().text()).contains("SQL 执行阶段失败");
        for (String assessment : List.of("DB_POOL_EXHAUSTION_OBSERVED", "NO_DB_POOL_EXHAUSTION_OBSERVED"))
            assertThatThrownBy(() -> output.parse(answer(assessment, evidence, "INSPECT_DB_QUERIES"), evidence, info)).isInstanceOf(RunFailure.class);
    }
    @Test void sqlPhaseNeedsMatchingEventCountersAndRule() throws Exception {
        var wrongEvent = evidence(5, 0, 1, 2, 0, 0, "DB_CONNECTION_ACQUIRE_TIMEOUT");
        assertThatThrownBy(() -> output.parse(answer("DB_SQL_EXECUTION_FAILURE_OBSERVED", wrongEvent, "INSPECT_DB_QUERIES"), wrongEvent, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
        var mixed = evidence(5, 1, 1, 2, 1, 0, "SQL_QUERY_FAILED");
        assertThatThrownBy(() -> output.parse(answer("DB_SQL_EXECUTION_FAILURE_OBSERVED", mixed, "INSPECT_DB_QUERIES"), mixed, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
        var impossibleCount = evidence(1, 0, 2, 1, 0, 0, "SQL_QUERY_FAILED");
        assertThatThrownBy(() -> output.parse(answer("DB_SQL_EXECUTION_FAILURE_OBSERVED", impossibleCount, "INSPECT_DB_QUERIES"), impossibleCount, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
        var valid = evidence(5, 0, 1, 2, 0, 0, "SQL_QUERY_FAILED");
        var wrongRule = List.of(new Evidence("DOC-DB-POOL-BASELINE#v1", "search_runbooks", "规则", "规则", Map.of()), valid.get(1), valid.get(2));
        assertThatThrownBy(() -> output.parse(answer("DB_SQL_EXECUTION_FAILURE_OBSERVED", wrongRule, "INSPECT_DB_QUERIES"), wrongRule, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_MISSING_EVIDENCE"));
    }
    @Test void capacityPeakWithoutOverlappingWaitingOrTimeoutLogIsNotEnough() throws Exception {
        for (var evidence : List.of(evidence(5, 1, 0, 1, 1, 0, "DB_CONNECTION_ACQUIRE_TIMEOUT"),
            evidence(5, 1, 0, 2, 0, 0, "DB_CONNECTION_ACQUIRE_TIMEOUT"), evidence(5, 1, 0, 2, 1, 2, null),
            evidence(5, 1, 0, 2, 1, 2, "SQL_QUERY_FAILED")))
            assertThatThrownBy(() -> output.parse(answer("DB_POOL_EXHAUSTION_OBSERVED", evidence, "VERIFY_DB_POOL_LIMITS"), evidence, info))
                .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }
    @Test void emptyWindowsAndHttpAssessmentsCannotBecomeDatabaseClaims() throws Exception {
        var empty = evidence(0, 0, 0, 1, 0, 0, null);
        assertThatThrownBy(() -> output.parse(answer("NO_DB_POOL_EXHAUSTION_OBSERVED", empty, "INSPECT_DB_QUERIES"), empty, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_NO_OBSERVATIONS"));
        var db = evidence(5, 0, 0, 1, 0, 0, null);
        assertThatThrownBy(() -> output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", db, "INSPECT_DB_QUERIES"), db, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }
    @Test void mismatchingMetricsAndLogsAreRejected() throws Exception {
        var original = evidence(5, 1, 0, 2, 1, 3, "DB_CONNECTION_ACQUIRE_TIMEOUT");
        var data = new LinkedHashMap<>(original.get(2).data()); data.put("acquisitionTimeoutCount", 2);
        var inconsistent = List.of(original.get(0), original.get(1), new Evidence("LOGS-DB", "query_error_logs", "日志", "错误事件", data));
        assertThatThrownBy(() -> output.parse(answer("DB_POOL_EXHAUSTION_OBSERVED", inconsistent, "VERIFY_DB_POOL_LIMITS"), inconsistent, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }
    @Test void conclusionsNeedTheCorrespondingDatabaseRuleAndBoundedChecks() throws Exception {
        var evidence = evidence(5, 1, 0, 2, 1, 3, "DB_CONNECTION_ACQUIRE_TIMEOUT");
        var wrongRule = List.of(new Evidence("DOC-DOWNSTREAM-TIMEOUT#v3", "search_runbooks", "规则", "规则", Map.of()), evidence.get(1), evidence.get(2));
        assertThatThrownBy(() -> output.parse(answer("DB_POOL_EXHAUSTION_OBSERVED", wrongRule, "VERIFY_DB_POOL_LIMITS"), wrongRule, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_MISSING_EVIDENCE"));
        assertThatThrownBy(() -> output.parse(answer("DB_POOL_EXHAUSTION_OBSERVED", evidence, "INSPECT_INVENTORY_LATENCY"), evidence, info))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_CHECKS_MISMATCH"));
    }
}
