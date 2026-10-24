package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.development.CiModelModels.*;
import io.github.mochiuaena.triage.development.CiModelModels.Preview;
import io.github.mochiuaena.triage.development.CiModels.Evidence;

import java.util.*;
import org.springframework.stereotype.Component;

/** Rejects unknown citations so a suggestion can only reference the exact blocks that were sent. */
@Component
public class CiModelSuggestionParser {
    private static final List<String> RELATIONS = List.of("MATCHES", "EXTENDS", "MISMATCHES", "UNCLEAR");
    private final ObjectMapper json;

    public CiModelSuggestionParser(ObjectMapper json) { this.json = json; }

    Suggestion parse(String text, Preview preview) {
        if (text == null || text.length() > 16_000) throw new IllegalArgumentException("Invalid model output");
        text = text.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        JsonNode node;
        try { node = json.readTree(text); } catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid model JSON"); }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing suggestion");
        String relation = value(node, "stageRelation", 20);
        if (!RELATIONS.contains(relation)) throw new IllegalArgumentException("Unknown stage relation");
        Set<String> allowed = new HashSet<>(preview.evidence().stream().map(Evidence::id).toList());
        var hypotheses = new ArrayList<Hypothesis>();
        for (JsonNode item : array(node, "hypotheses", 6)) hypotheses.add(new Hypothesis(value(item, "text", 600), references(item, allowed)));
        var verifications = new ArrayList<Verification>();
        for (JsonNode item : array(node, "verifications", 8)) verifications.add(new Verification(value(item, "text", 600), references(item, allowed)));
        return new Suggestion(value(node, "summary", 1200), value(node, "knownStage", 800), relation,
            List.copyOf(hypotheses), List.copyOf(verifications), references(node, allowed));
    }

    private static List<String> references(JsonNode value, Set<String> allowed) {
        var ids = new LinkedHashSet<String>();
        for (var ref : array(value, "evidenceIds", 6)) {
            String id = string(ref, 40);
            if (!allowed.contains(id)) throw new IllegalArgumentException("Unknown evidence");
            ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Evidence required");
        return List.copyOf(ids);
    }

    private static JsonNode array(JsonNode value, String field, int max) {
        JsonNode node = value.path(field);
        if (!node.isArray() || node.size() > max) throw new IllegalArgumentException("Invalid list");
        return node;
    }

    private static String value(JsonNode value, String field, int max) { return string(value.path(field), max); }

    private static String string(JsonNode node, int max) {
        if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max) throw new IllegalArgumentException("Invalid text");
        return IssueEvidenceText.clean(node.asText());
    }
}
