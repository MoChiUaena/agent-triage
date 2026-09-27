package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunFailure;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveModelOutputTest {
    @Test void collectedSourceNamesDoNotJustifySuccessWhenNoRequestsWereObserved() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var evidence = List.of(
            new Evidence("DOC-LIVE", "search_runbooks", "规则", "规则", Map.of()),
            new Evidence("METRICS-LIVE", "read_service_metrics", "指标", "无请求", Map.of("requestCount", 0)),
            new Evidence("LOGS-LIVE", "query_error_logs", "日志", "无错误", Map.of()));
        var diagnosis = new Diagnosis(
            List.of(new Finding("没有请求。", List.of("METRICS-LIVE")), new Finding("没有错误。", List.of("LOGS-LIVE"))),
            List.of(new Finding("已经判断为正常。", List.of("DOC-LIVE", "METRICS-LIVE", "LOGS-LIVE"))),
            List.of("继续观察。"), "缺少请求。");
        String answer = json.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", diagnosis));
        var output = new ModelOutput(json);
        assertThatThrownBy(() -> output.parse(answer, evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure ->
                org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo("MODEL_NO_OBSERVATIONS"));
    }

    @Test void successCannotCiteATimeoutRuleForANormalWindow() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        var evidence = List.of(
            new Evidence("DOC-DOWNSTREAM-TIMEOUT#v2", "search_runbooks", "超时规则", "规则", Map.of()),
            new Evidence("METRICS-LIVE", "read_service_metrics", "指标", "有请求且无超时",
                Map.of("requestCount", 5, "downstreamTimeoutRate", 0.0)),
            new Evidence("LOGS-LIVE", "query_error_logs", "日志", "无错误", Map.of()));
        var diagnosis = new Diagnosis(
            List.of(new Finding("当前无超时。", List.of("METRICS-LIVE")), new Finding("日志为空。", List.of("LOGS-LIVE"))),
            List.of(new Finding("本次窗口正常。", List.of("DOC-DOWNSTREAM-TIMEOUT#v2", "METRICS-LIVE"))),
            List.of("继续观察。"), "缺少其他依赖数据。");
        String answer = json.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", diagnosis));
        var output = new ModelOutput(json);
        assertThatThrownBy(() -> output.parse(answer, evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure ->
                org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo("INVALID_MODEL_OUTPUT"));
    }

    @Test void quotedTraceIdMustExactlyMatchThisRunsErrorLog() throws Exception {
        var json = JsonMapper.builder().findAndAddModules().build();
        String correct = "1c9c272c-e593-46ad-9790-2d7021a14d48";
        String mistyped = "1c9c272-e593-46ad-2d7021a14d48";
        var evidence = List.of(
            new Evidence("DOC-DOWNSTREAM-TIMEOUT#v2", "search_runbooks", "超时规则", "规则", Map.of()),
            new Evidence("METRICS-LIVE", "read_service_metrics", "指标", "有请求且超时",
                Map.of("requestCount", 5, "downstreamTimeoutRate", 1.0)),
            new Evidence("LOGS-LIVE", "query_error_logs", "日志", "库存请求超时",
                Map.of("entries", List.of(Map.of("traceId", correct)))));
        var output = new ModelOutput(json);
        var wrong = new Diagnosis(
            List.of(new Finding("5 次请求全部超时。", List.of("METRICS-LIVE")),
                new Finding("错误请求 traceId 为 " + mistyped + "。", List.of("LOGS-LIVE"))),
            List.of(new Finding("库存调用可能超时。", List.of("DOC-DOWNSTREAM-TIMEOUT#v2", "METRICS-LIVE", "LOGS-LIVE"))),
            List.of("检查库存服务。"), "缺少库存内部指标。");
        String wrongAnswer = json.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", wrong));
        assertThatThrownBy(() -> output.parse(wrongAnswer, evidence))
            .isInstanceOfSatisfying(RunFailure.class, failure ->
                org.assertj.core.api.Assertions.assertThat(failure.code()).isEqualTo("MODEL_UNSUPPORTED_TRACE_ID"));

        var correctAnswer = new Diagnosis(
            List.of(new Finding("5 次请求全部超时。", List.of("METRICS-LIVE")),
                new Finding("错误请求 traceId 为 " + correct + "。", List.of("LOGS-LIVE"))),
            wrong.possibleCauses(), wrong.nextSteps(), wrong.uncertainty());
        String valid = json.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", correctAnswer));
        org.assertj.core.api.Assertions.assertThat(output.parse(valid, evidence).status())
            .isEqualTo(io.github.mochiuaena.triage.domain.TriageModel.Status.SUCCEEDED);
    }
}
