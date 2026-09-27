package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.*;
import org.springframework.ai.converter.BeanOutputConverter;
import java.util.*;
import java.util.stream.Collectors;

final class ModelOutput {
    enum Outcome { SUCCEEDED, INSUFFICIENT_EVIDENCE }
    record Response(Outcome status, Diagnosis diagnosis) {}

    private final ObjectMapper json;
    private final String format;

    ModelOutput(ObjectMapper mapper) {
        json = strictMapper(mapper);
        // Use Spring AI for the schema, but parse locally: converter errors include raw model text in logs.
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

    TriageEngine.Decision parse(String text, List<Evidence> evidence) {
        try {
            if (text == null || text.length() > 16_000) throw new IllegalArgumentException();
            String content = text.strip();
            if ((content.startsWith("```json\n") || content.startsWith("```\n")) && content.endsWith("```"))
                content = content.substring(content.indexOf('\n') + 1, content.length() - 3).strip();
            Response response = json.readValue(content, Response.class);
            if (response == null || response.status() == null) throw new IllegalArgumentException();
            if (response.status() == Outcome.SUCCEEDED) rejectEmptyWindow(evidence);
            EvidenceValidator.validate(response.diagnosis(), evidence);
            Diagnosis diagnosis = response.diagnosis();
            if (response.status() == Outcome.SUCCEEDED) validateSuccess(diagnosis, evidence);
            else if (!diagnosis.possibleCauses().isEmpty()) throw new IllegalArgumentException();
            return new TriageEngine.Decision(Status.valueOf(response.status().name()), diagnosis);
        } catch (RunFailure e) {
            throw e;
        } catch (Exception e) {
            throw new RunFailure("INVALID_MODEL_OUTPUT", "模型结论的格式或证据引用无效，未保存为排查结果。");
        }
    }

    private void rejectEmptyWindow(List<Evidence> evidence) {
        evidence.stream().filter(item -> item.source().equals("read_service_metrics")).findFirst().ifPresent(metrics -> {
            if (metrics.data().get("requestCount") instanceof Number count && count.intValue() <= 0)
                throw new RunFailure("MODEL_NO_OBSERVATIONS", "模型试图在无请求窗口生成成功结论，已拒绝。");
        });
    }

    private void validateSuccess(Diagnosis diagnosis, List<Evidence> evidence) {
        if (diagnosis.observations().isEmpty() || diagnosis.possibleCauses().isEmpty()) throw new IllegalArgumentException();
        Evidence metrics = evidence.stream().filter(item -> item.source().equals("read_service_metrics")).findFirst().orElseThrow();
        if (!(metrics.data().get("requestCount") instanceof Number count) || count.intValue() <= 0)
            throw new IllegalArgumentException();
        Map<String, Evidence> byId = evidence.stream().collect(Collectors.toMap(Evidence::id, e -> e));
        Set<String> citedSources = new HashSet<>();
        for (Finding finding : diagnosis.observations()) {
            Set<String> sources = sources(finding, byId);
            if (Collections.disjoint(sources, Set.of("read_service_metrics", "query_error_logs"))) throw new IllegalArgumentException();
            citedSources.addAll(sources);
        }
        for (Finding finding : diagnosis.possibleCauses()) {
            Set<String> sources = sources(finding, byId);
            if (!sources.contains("search_runbooks") || Collections.disjoint(sources, Set.of("read_service_metrics", "query_error_logs")))
                throw new IllegalArgumentException();
            citedSources.addAll(sources);
        }
        if (!citedSources.containsAll(Set.of("search_runbooks", "read_service_metrics", "query_error_logs")))
            throw new IllegalArgumentException();
    }

    private Set<String> sources(Finding finding, Map<String, Evidence> evidence) {
        return finding.evidenceIds().stream().map(id -> evidence.get(id).source()).collect(Collectors.toSet());
    }
}
