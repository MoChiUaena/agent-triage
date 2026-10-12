package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import io.github.mochiuaena.triage.tools.ToolContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class MultiDownstreamDiagnosisTest {
    private final ServiceInfo service = new ServiceInfo("checkout-service", "结算服务", null, null,
        List.of(new Downstream("inventory-service", "库存服务"), new Downstream("payment-service", "支付服务")));

    @Test void questionsMayNameEitherConfiguredDownstream() {
        var target = new ServiceRegistry.Target(service, java.net.URI.create("http://127.0.0.1:18080"),
            ServiceRegistry.Protocol.OBSERVATIONS_V5, 15, false);
        var context = new ToolContext(service.id(), 5, Scenario.OBSERVED, java.time.Instant.parse("2026-10-12T00:00:00Z"), target);
        assertThat(QuestionScope.supports("支付服务怎么回事？", context)).isTrue();
        assertThat(QuestionScope.supports("payment-service 怎么回事？", context)).isTrue();
    }

    @Test void onlyTheTimedOutDownstreamIsNamedAsThePossibleCause() {
        var metrics = new Evidence("METRICS", "read_service_metrics", "指标", "库存超时 1 次，支付超时 0 次", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "requestCount", 5, "timeoutCount", 1, "downstreamTimeoutRate", 0.2,
            "downstreams", List.of(row("inventory-service", 2, 1), row("payment-service", 3, 0))));
        var logs = new Evidence("LOGS", "query_error_logs", "日志", "最近 1 条错误事件", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "returnedCount", 1, "entries", List.of(Map.of("traceId", "t"))));
        var rule = new Evidence("DOC-DOWNSTREAM-TIMEOUT#v1", "search_runbooks", "规则", "超时规则", Map.of());

        var result = new DemoReasoner().diagnose(List.of(metrics, logs, rule), service);
        assertThat(result.possibleCauses()).hasSize(1);
        assertThat(result.possibleCauses().getFirst().text()).contains("库存服务").doesNotContain("支付服务", "null");
        assertThat(result.nextSteps()).anySatisfy(step -> assertThat(step).contains("inventory-service"));
        assertThat(result.nextSteps()).allSatisfy(step -> assertThat(step).doesNotContain("null"));
    }

    @Test void otherErrorsInANoTimeoutWindowKeepTheConclusionIncomplete() {
        var metrics = new Evidence("METRICS", "read_service_metrics", "指标", "两个下游都没有超时", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "requestCount", 5, "timeoutCount", 0,
            "downstreamTimeoutRate", 0.0, "downstreams", List.of(row("inventory-service", 2, 0), row("payment-service", 3, 0))));
        var logs = new Evidence("LOGS", "query_error_logs", "日志", "另有错误事件", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "returnedCount", 1, "entries", List.of(Map.of("traceId", "t"))));
        var rule = new Evidence("DOC-HEALTHY-BASELINE#v1", "search_runbooks", "规则", "无超时规则", Map.of());
        var result = new DemoReasoner().diagnose(List.of(metrics, logs, rule), service);
        assertThat(result.possibleCauses()).isEmpty();
        assertThat(result.uncertainty()).contains("错误").doesNotContain("null");
    }

    @Test void timeoutCountersWithoutAnErrorEventRemainIncomplete() {
        var metrics = new Evidence("METRICS", "read_service_metrics", "指标", "库存超时 1 次", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "requestCount", 5, "timeoutCount", 1,
            "downstreamTimeoutRate", 0.2, "downstreams", List.of(row("inventory-service", 2, 1), row("payment-service", 3, 0))));
        var logs = new Evidence("LOGS", "query_error_logs", "日志", "没有错误事件", Map.of(
            "observationType", "HTTP_DOWNSTREAMS", "returnedCount", 0, "entries", List.of()));
        var rule = new Evidence("DOC-DOWNSTREAM-TIMEOUT#v1", "search_runbooks", "规则", "超时规则", Map.of());
        var result = new DemoReasoner().diagnose(List.of(metrics, logs, rule), service);
        assertThat(result.possibleCauses()).isEmpty();
        assertThat(result.uncertainty()).contains("事件");
    }

    private Map<String, Object> row(String id, int requests, int timeouts) {
        return Map.of("id", id, "requestCount", requests, "timeoutCount", timeouts);
    }
}
