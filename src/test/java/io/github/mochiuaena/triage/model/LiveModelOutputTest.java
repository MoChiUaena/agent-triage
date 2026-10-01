package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class LiveModelOutputTest {
    @Test void registeredLabelsComeFromTheServerAndReplaceOrderSpecificWording() {
        var evidence = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v3");
        var info = new ServiceInfo("checkout-service", "结算服务", "stock-service", "商品服务");
        var result = output.parse(answer("DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence, info).decision().diagnosis();
        assertThat(result.possibleCauses().getFirst().text()).contains("商品服务", "结算服务").doesNotContain("订单", "库存");
        assertThat(result.nextSteps()).allSatisfy(step -> assertThat(step).doesNotContain("订单", "库存"));
    }

    @Test void oldRunWithoutServiceInfoRemainsReadable() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var old = new Run(java.util.UUID.randomUUID(), "订单为何变慢", "order-service", 15, Scenario.NORMAL,
            "DEMO", false, Status.SUCCEEDED, java.time.Instant.now(), null, 0, List.of(), List.of(), null, null);
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(old);
        tree.remove("serviceInfo");
        var decoded = mapper.treeToValue(tree, Run.class);
        assertThat(decoded.service()).isEqualTo("order-service");
        assertThat(decoded.serviceInfo()).isNull();
    }
    private final ModelOutput output = new ModelOutput(JsonMapper.builder().findAndAddModules().build());
    private static final String TRACE = "1c9c272c-e593-46ad-9790-2d7021a14d48";

    private List<Evidence> evidence(int count, double rate, boolean logs, String rule) {
        var entries = logs ? List.of(Map.of("traceId", TRACE, "message", "request timeout after 300ms")) : List.of();
        return List.of(
            new Evidence(rule, "search_runbooks", "规则", "不是当前请求记录", Map.of()),
            new Evidence("METRICS-LIVE", "read_service_metrics", "指标", "本窗口处理 " + count + " 个请求；超时率 " + rate + "。",
                Map.of("requestCount", count, "downstreamTimeoutRate", rate, "orderP95Ms", 310.1, "downstreamP95Ms", 309.9)),
            new Evidence("LOGS-LIVE", "query_error_logs", "日志", logs ? "本窗口记录库存请求超时。" : "本窗口未记录库存请求错误。",
                Map.of("entries", entries, "returnedCount", entries.size(), "timeoutCount", logs ? 3 : 0)));
    }

    private String answer(String assessment, String rule) {
        String checks = assessment.equals("NO_DOWNSTREAM_TIMEOUT_OBSERVED")
            ? "\"FIND_SLOW_REQUEST\",\"COLLECT_RESOURCE_METRICS\"" : "\"INSPECT_INVENTORY_LATENCY\",\"CORRELATE_TRACE\"";
        return "{\"assessment\":\"" + assessment + "\",\"evidenceIds\":[\"METRICS-LIVE\",\"LOGS-LIVE\",\"" + rule
            + "\"],\"nextChecks\":[" + checks + "]}";
    }

    @Test void timeoutTextCopiesObservedSummariesWithoutRewritingNumbersOrTraceIds() {
        var evidence = evidence(5, 0.6, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        var decision = output.parse(answer("DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence).decision();
        assertThat(decision.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(decision.diagnosis().observations()).extracting(Finding::text)
            .containsExactly(evidence.get(1).summary(), evidence.get(2).summary());
        assertThat(decision.diagnosis().possibleCauses().getFirst().text()).contains("可能影响")
            .doesNotContain("不可用", "读取超时", "完全", TRACE);
        assertThat(decision.diagnosis().nextSteps()).allSatisfy(step -> assertThat(step).doesNotContain(TRACE));
    }

    @Test void noTimeoutDoesNotClaimOverallServiceHealth() {
        var evidence = evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2");
        var decision = output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence).decision();
        assertThat(decision.diagnosis().possibleCauses().getFirst().text())
            .contains("本窗口未发现库存调用超时", "仍需补充观测").doesNotContain("服务运行正常", "服务健康");
    }
    @Test void responseErrorsCannotBeAcceptedAsANoTimeoutConclusion() {
        var inputs = new java.util.ArrayList<>(evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2"));
        var metrics = inputs.get(1); var data = new java.util.LinkedHashMap<>(metrics.data());
        data.put("responseStatuses", Map.of("informational", 0, "successful", 4, "redirection", 0,
            "clientError", 1, "serverError", 0, "unknown", 0));
        inputs.set(1, new Evidence(metrics.id(), metrics.source(), metrics.title(), metrics.summary(), data));
        assertThatThrownBy(() -> output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", inputs.getFirst().id()), inputs))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }

    @Test void emptyWindowCannotBecomeAClaim() {
        var evidence = evidence(0, 0, false, "DOC-HEALTHY-BASELINE#v2");
        assertThatThrownBy(() -> output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_NO_OBSERVATIONS"));
    }

    @Test void assessmentCannotContradictMetrics() {
        var evidence = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        assertThatThrownBy(() -> output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }

    @Test void timeoutNeedsBothMetricsAndActualErrorEvents() {
        var evidence = evidence(5, 1, false, "DOC-DOWNSTREAM-TIMEOUT#v2");
        assertThatThrownBy(() -> output.parse(answer("DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }

    @Test void wrongScenarioRuleCannotSupportASuccessfulClaim() {
        var evidence = evidence(5, 0, false, "DOC-DOWNSTREAM-TIMEOUT#v2");
        assertThatThrownBy(() -> output.parse(answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id()), evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_MISSING_EVIDENCE"));
    }

    @Test void metricsAndLogsAloneDoNotSatisfyTheSuccessfulSelectionContract() {
        var evidence = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        String answer = "{\"assessment\":\"DOWNSTREAM_TIMEOUT_OBSERVED\",\"evidenceIds\":[\"METRICS-LIVE\",\"LOGS-LIVE\"],"
            + "\"nextChecks\":[\"CORRELATE_TRACE\"]}";
        assertThatThrownBy(() -> output.parse(answer, evidence)).isInstanceOfSatisfying(RunFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo("MODEL_MISSING_EVIDENCE");
            assertThat(failure.getMessage()).contains("没有选择", "匹配的排障规则");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"assessment\":\"INVENTORY_UNAVAILABLE\",\"evidenceIds\":[],\"nextChecks\":[\"COLLECT_OBSERVATIONS\"]}",
        "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[\"RESTART_SERVICE\"]}",
        "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[],\"answer\":\"服务完全正常\"}",
        "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[\"COLLECT_OBSERVATIONS\"],\"traceId\":\"1c9c272-e593-46ad-2d7021a14d48\"}",
        "{\"status\":\"SUCCEEDED\",\"diagnosis\":{\"text\":\"库存不可用\"}}",
        "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[\"missing\"],\"nextChecks\":[\"COLLECT_OBSERVATIONS\"]}",
        "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[\"COLLECT_OBSERVATIONS\",\"COLLECT_OBSERVATIONS\"]}"
    })
    void unboundedClaimsTextOrInvalidSelectionsAreRejected(String answer) {
        assertThatThrownBy(() -> output.parse(answer, evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2")))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("INVALID_MODEL_OUTPUT"));
    }

    @Test void insufficientEvidenceCanSelectDocumentsWithoutInventingObservations() {
        var decision = output.parse("{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[\"DOC-HEALTHY-BASELINE#v2\"],"
            + "\"nextChecks\":[\"COLLECT_OBSERVATIONS\"]}", List.of(evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2").getFirst())).decision();
        assertThat(decision.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(decision.diagnosis().observations()).isEmpty();
        assertThat(decision.diagnosis().possibleCauses()).isEmpty();
    }

    @Test void oldHistoryWithoutAnAssessmentRemainsReadable() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        ModelExecution old = json.readValue("{\"configuredModel\":\"old-model\",\"responseModel\":null,\"calls\":2,\"usage\":null,\"source\":null}",
            ModelExecution.class);
        assertThat(old.calls()).isEqualTo(2);
        assertThat(old.assessment()).isNull();
        assertThat(old.nextChecks()).isNull();
        assertThat(old.requestedNextChecks()).isNull();
    }

    @Test void schemaExposesEveryAllowedSelectionToTheProvider() throws Exception {
        for (var value : ModelOutput.Assessment.values()) assertThat(output.format() + output.format(true)).contains(value.name());
        for (var value : ModelOutput.Check.values()) assertThat(output.format() + output.format(true)).contains(value.name());
        assertThat(output.format()).doesNotContain("DB_POOL_EXHAUSTION_OBSERVED", "INSPECT_DB_QUERIES");
        assertThat(output.format(true)).doesNotContain("DOWNSTREAM_TIMEOUT_OBSERVED", "INSPECT_INVENTORY_LATENCY");
        var schema = JsonMapper.builder().build().readTree(output.format());
        assertThat(schema.path("required").toString()).contains("assessment", "evidenceIds", "nextChecks");
        assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
        assertThat(schema.path("properties").path("nextChecks").path("minItems").intValue()).isEqualTo(1);
        assertThat(schema.path("properties").path("nextChecks").path("maxItems").intValue()).isEqualTo(5);
    }

    @Test void rejectionReasonsDoNotEchoModelTextOrUnknownIdentifiers() {
        var evidence = evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2");
        var answers = Map.of(
            "{\"private-value\":\"private-value\"}", "契约之外的字段",
            "{\"assessment\":\"private-value\"}", "不在允许列表",
            "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[\"private-value\"],\"nextChecks\":[\"COLLECT_OBSERVATIONS\"]}", "不属于本次执行",
            "private-value", "不是有效的单个 JSON");
        answers.forEach((answer, reason) -> assertThatThrownBy(() -> output.parse(answer, evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo("INVALID_MODEL_OUTPUT");
                assertThat(failure.getMessage()).contains(reason).doesNotContain("private-value");
            }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COLLECT_OBSERVATIONS", "SEARCH_MATCHING_RULE", "CORRELATE_TRACE"})
    void aNormalWindowCannotRepeatCompletedQueriesOrCorrelateAbsentTraces(String check) {
        var evidence = evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2");
        String answer = answer("NO_DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id())
            .replace("\"FIND_SLOW_REQUEST\",\"COLLECT_RESOURCE_METRICS\"", "\"" + check + "\"");
        assertThatThrownBy(() -> output.parse(answer, evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_CHECKS_MISMATCH"));
    }

    @Test void omittingAvailableEvidenceDoesNotMakeItsCollectionANewCheck() {
        String answer = "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[\"COLLECT_OBSERVATIONS\"]}";
        assertThatThrownBy(() -> output.parse(answer, evidence(5, 0, false, "DOC-HEALTHY-BASELINE#v2")))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_CHECKS_MISMATCH"));
    }

    @Test void correlationRequiresAnActualTraceInTheAvailableLogEvidence() {
        var original = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        var log = new Evidence("LOGS-LIVE", "query_error_logs", "日志", "超时", Map.of(
            "entries", List.of(Map.of("message", "timeout")), "returnedCount", 1, "timeoutCount", 1));
        assertThatThrownBy(() -> output.parse(answer("DOWNSTREAM_TIMEOUT_OBSERVED", original.getFirst().id()),
            List.of(original.get(0), original.get(1), log)))
            .isInstanceOfSatisfying(RunFailure.class, failure -> assertThat(failure.code()).isEqualTo("MODEL_CHECKS_MISMATCH"));
    }

    @Test void timeoutSuggestionsArePrioritizedWithoutInventingOrLosingOriginalChoices() {
        var evidence = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        String answer = answer("DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id())
            .replace("\"INSPECT_INVENTORY_LATENCY\",\"CORRELATE_TRACE\"",
                "\"FIND_SLOW_REQUEST\",\"COLLECT_RESOURCE_METRICS\",\"VERIFY_REQUEST_TIMEOUT\",\"INSPECT_INVENTORY_LATENCY\",\"CORRELATE_TRACE\"");
        var parsed = output.parse(answer, evidence);
        assertThat(parsed.requestedNextChecks()).hasSize(5);
        assertThat(parsed.nextChecks()).containsExactly(ModelOutput.Check.CORRELATE_TRACE, ModelOutput.Check.INSPECT_INVENTORY_LATENCY);
        assertThat(parsed.requestedNextChecks()).containsAll(parsed.nextChecks());
        assertThat(parsed.decision().diagnosis().nextSteps()).hasSize(2);
    }

    @Test void priorityDoesNotInsertAnUnselectedCheck() {
        var evidence = evidence(5, 1, true, "DOC-DOWNSTREAM-TIMEOUT#v2");
        String answer = answer("DOWNSTREAM_TIMEOUT_OBSERVED", evidence.getFirst().id())
            .replace("\"INSPECT_INVENTORY_LATENCY\",\"CORRELATE_TRACE\"", "\"COLLECT_RESOURCE_METRICS\"");
        assertThat(output.parse(answer, evidence).nextChecks()).containsExactly(ModelOutput.Check.COLLECT_RESOURCE_METRICS);
    }
}
