package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.*;
import org.springframework.ai.converter.BeanOutputConverter;
import java.util.*;
import java.util.stream.Collectors;

/** The model selects claims and evidence; only application code writes diagnostic text. */
final class ModelOutput {
    enum Assessment { DOWNSTREAM_TIMEOUT_OBSERVED, NO_DOWNSTREAM_TIMEOUT_OBSERVED, INSUFFICIENT_EVIDENCE }
    enum Check {
        INSPECT_INVENTORY_LATENCY, CORRELATE_TRACE, VERIFY_REQUEST_TIMEOUT, COLLECT_RESOURCE_METRICS,
        FIND_SLOW_REQUEST, COLLECT_OBSERVATIONS, SEARCH_MATCHING_RULE
    }
    record Response(Assessment assessment, List<String> evidenceIds, List<Check> nextChecks) {}
    record Parsed(Assessment assessment, TriageEngine.Decision decision) {}

    private final ObjectMapper json;
    private final String format;

    ModelOutput(ObjectMapper mapper) {
        json = strictMapper(mapper);
        format = new BeanOutputConverter<>(Response.class, json).getFormat();
    }

    static ObjectMapper strictMapper(ObjectMapper mapper) {
        ObjectMapper copy = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        var textCoercion = copy.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
            textCoercion.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        copy.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        copy.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
            .maxStringLength(20_000).maxNumberLength(20).build());
        return copy;
    }

    String format() { return format; }

    Parsed parse(String text, List<Evidence> evidence) {
        try {
            if (text == null || text.length() > 16_000) throw new IllegalArgumentException();
            String content = text.strip();
            if ((content.startsWith("```json\n") || content.startsWith("```\n")) && content.endsWith("```"))
                content = content.substring(content.indexOf('\n') + 1, content.length() - 3).strip();
            Response response = json.readValue(content, Response.class);
            if (response == null || response.assessment() == null || response.evidenceIds() == null
                || response.evidenceIds().size() > 15 || new HashSet<>(response.evidenceIds()).size() != response.evidenceIds().size()
                || response.nextChecks() == null || response.nextChecks().isEmpty() || response.nextChecks().size() > 5
                || response.nextChecks().contains(null)
                || new HashSet<>(response.nextChecks()).size() != response.nextChecks().size()) throw new IllegalArgumentException();
            Map<String, Evidence> byId = evidence.stream().collect(Collectors.toMap(Evidence::id, item -> item));
            if (!byId.keySet().containsAll(response.evidenceIds())) throw new IllegalArgumentException();
            List<Evidence> selected = response.evidenceIds().stream().map(byId::get).toList();
            if (response.assessment() != Assessment.INSUFFICIENT_EVIDENCE) validateAssessment(response.assessment(), selected);
            Diagnosis diagnosis = render(response, selected);
            EvidenceValidator.validate(diagnosis, evidence);
            return new Parsed(response.assessment(), new TriageEngine.Decision(response.assessment() == Assessment.INSUFFICIENT_EVIDENCE
                ? Status.INSUFFICIENT_EVIDENCE : Status.SUCCEEDED, diagnosis));
        } catch (RunFailure e) { throw e; }
        catch (Exception e) {
            throw new RunFailure("INVALID_MODEL_OUTPUT", "模型判断类型、检查项或证据选择无效，未保存为排查结果。");
        }
    }

    private void validateAssessment(Assessment assessment, List<Evidence> selected) {
        Evidence metrics = one(selected, "read_service_metrics");
        Evidence logs = one(selected, "query_error_logs");
        if (!(metrics.data().get("requestCount") instanceof Number count) || count.longValue() <= 0)
            throw new RunFailure("MODEL_NO_OBSERVATIONS", "模型试图在无请求窗口生成成功判断，已拒绝。");
        double rate = number(metrics, "downstreamTimeoutRate");
        if (rate > 1) throw new IllegalArgumentException();
        number(metrics, "orderP95Ms");
        number(metrics, "downstreamP95Ms");
        if (!(logs.data().get("entries") instanceof List<?> entries)
            || !(logs.data().get("returnedCount") instanceof Number returned)
            || returned.intValue() != entries.size()
            || !(logs.data().get("timeoutCount") instanceof Number timeouts)) throw new IllegalArgumentException();
        boolean timeout = assessment == Assessment.DOWNSTREAM_TIMEOUT_OBSERVED;
        if ((timeout && (rate <= 0 || timeouts.longValue() <= 0 || entries.isEmpty()))
            || (!timeout && (rate != 0 || timeouts.longValue() != 0 || !entries.isEmpty())))
            throw new RunFailure("MODEL_ASSESSMENT_MISMATCH", "模型判断与本窗口指标或错误事件不符，已拒绝。");
        String rule = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
        if (selected.stream().noneMatch(item -> item.source().equals("search_runbooks") && item.id().startsWith(rule)))
            throw new IllegalArgumentException();
    }

    private Evidence one(List<Evidence> selected, String source) {
        List<Evidence> matches = selected.stream().filter(item -> item.source().equals(source)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException();
        return matches.getFirst();
    }

    private double number(Evidence evidence, String name) {
        if (!(evidence.data().get(name) instanceof Number number)) throw new IllegalArgumentException();
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException();
        return value;
    }

    private Diagnosis render(Response response, List<Evidence> selected) {
        List<Finding> observations = selected.stream()
            .filter(item -> Set.of("read_service_metrics", "query_error_logs").contains(item.source()))
            .map(item -> new Finding(item.summary(), List.of(item.id()))).toList();
        List<Finding> causes = List.of();
        String uncertainty = "本次证据仍不足以支持当前状态判断。模型只选择了证据和检查项，关键措辞由应用生成。";
        if (response.assessment() != Assessment.INSUFFICIENT_EVIDENCE) {
            boolean timeout = response.assessment() == Assessment.DOWNSTREAM_TIMEOUT_OBSERVED;
            String required = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
            Evidence rule = selected.stream().filter(item -> item.source().equals("search_runbooks") && item.id().startsWith(required))
                .findFirst().orElseThrow();
            List<String> ids = List.of(one(selected, "read_service_metrics").id(), one(selected, "query_error_logs").id(), rule.id());
            causes = List.of(new Finding(timeout
                ? "本窗口存在库存调用超时，可能影响订单查询；建议优先验证库存调用路径。"
                : "本窗口未发现库存调用超时证据；其他延迟来源仍需补充观测。", ids));
            uncertainty = "本次只覆盖查询窗口内已采集的订单请求、库存调用和错误事件。未采集库存内部、网络、数据库与连接池指标，"
                + "不能确认内部根因或服务整体健康。模型选择判断类型与证据，关键结论由应用按证据生成。";
        }
        List<String> nextSteps = response.nextChecks().stream().map(this::checkText).toList();
        return new Diagnosis(observations, causes, nextSteps, uncertainty);
    }

    private String checkText(Check check) {
        return switch (check) {
            case INSPECT_INVENTORY_LATENCY -> "核对同一窗口内库存接口的实际处理耗时和错误率。";
            case CORRELATE_TRACE -> "使用错误事件中已有的 traceId 对照订单与库存请求的调用耗时。";
            case VERIFY_REQUEST_TIMEOUT -> "核对订单客户端的请求总时限，并结合库存接口耗时验证。";
            case COLLECT_RESOURCE_METRICS -> "补充库存服务 CPU、连接池、网络及数据库指标后再判断内部原因。";
            case FIND_SLOW_REQUEST -> "找到具体慢请求的时间和 traceId，缩小查询范围。";
            case COLLECT_OBSERVATIONS -> "补充当前窗口的订单指标和错误日志后再判断。";
            case SEARCH_MATCHING_RULE -> "检索与本次正常或超时观测匹配的排障规则。";
        };
    }
}
