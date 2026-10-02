package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestExecutionDiagnosisTest {
    private Evidence metrics(long failures) {
        return new Evidence("metrics", "read_service_metrics", "请求", "两个请求，一个执行异常", Map.of("service", "entry-service",
            "windowStart", "2026-10-02T00:00:00Z", "windowEnd", "2026-10-02T00:01:00Z", "observationType", "HTTP_REQUESTS",
            "requestCount", 2, "responseStatuses", Map.of("serverError", 0, "unknown", 1),
            "requestFailures", Map.of("executionFailures", failures, "serverErrorResponses", 0, "asyncTimeouts", 0, "asyncErrors", 0, "handledExceptions", 0)));
    }
    private Evidence logs(String code) {
        return new Evidence("logs", "query_error_logs", "错误", "一个请求异常", Map.of("service", "entry-service",
            "windowStart", "2026-10-02T00:00:00Z", "windowEnd", "2026-10-02T00:01:00Z", "observationType", "HTTP_REQUESTS", "requestCount", 2,
            "requestFailures", Map.of("executionFailures", 1, "serverErrorResponses", 0, "asyncTimeouts", 0, "asyncErrors", 0, "handledExceptions", 0),
            "returnedCount", 1, "entries", List.of(Map.of("code", code, "responseClass", 0, "level", "ERROR", "traceId", "fixture", "timestamp", "2026-10-02T00:01:00Z"))));
    }
    @Test void matchingExecutionCounterEventAndRuleSupportOnlyTheRequestStage() {
        var rule = new Evidence("DOC-REQUEST-EXECUTION-FAILURE#v1", "search_runbooks", "执行异常", "规则", Map.of());
        var result = new DemoReasoner().diagnose(List.of(metrics(1), logs("REQUEST_EXECUTION_FAILED"), rule), new ServiceInfo("entry-service", "入口服务", null, null));
        assertThat(result.possibleCauses()).hasSize(1);
        assertThat(result.possibleCauses().getFirst().text()).contains("请求执行", "异常").doesNotContain("下游超时", "连接池耗尽");
        assertThat(result.possibleCauses().getFirst().evidenceIds()).containsExactly("metrics", "logs", rule.id());
    }
    @Test void missingRuleWrongEventAndMismatchedCountersNeverSupportExecutionFailure() {
        var rule = new Evidence("DOC-REQUEST-EXECUTION-FAILURE#v1", "search_runbooks", "执行异常", "规则", Map.of());
        for (var evidence : List.of(List.of(metrics(1), logs("REQUEST_EXECUTION_FAILED")),
            List.of(metrics(1), logs("HTTP_SERVER_ERROR_RESPONSE"), rule), List.of(metrics(0), logs("REQUEST_EXECUTION_FAILED"), rule))) {
            assertThat(new DemoReasoner().diagnose(evidence, new ServiceInfo("entry-service", "入口服务", null, null)).possibleCauses()).isEmpty();
        }
    }
}
