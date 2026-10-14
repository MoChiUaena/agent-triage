package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.development.IssueModels.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class IssueProposalParser {
    private final ObjectMapper json;
    public IssueProposalParser(ObjectMapper json) { this.json = json; }
    Proposal parse(String text, Preview preview) {
        if (text == null || text.length() > 16_000) throw new IllegalArgumentException("Invalid model output");
        text = text.strip(); if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        JsonNode node;
        try { node = json.readTree(text); } catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid model JSON"); }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing proposal");
        String category = value(node, "category", 30), priority = value(node, "priority", 20);
        if (!Set.of("BUG", "FEATURE_REQUEST", "QUESTION", "DOCUMENTATION", "UNKNOWN").contains(category)
            || !Set.of("P1", "P2", "P3", "UNSPECIFIED").contains(priority)) throw new IllegalArgumentException("Unknown classification");
        Set<String> allowed = new HashSet<>(preview.evidence().stream().map(Evidence::id).toList());
        var questions = new ArrayList<String>(); var actions = new ArrayList<Action>(); var owners = new ArrayList<OwnerCandidate>();
        for (JsonNode question : array(node, "questions", 6)) questions.add(string(question, 500));
        for (JsonNode action : array(node, "actions", 8)) actions.add(new Action(value(action, "text", 600), references(action, allowed)));
        for (JsonNode candidate : array(node, "ownerCandidates", 3)) {
            String login = value(candidate, "login", 40);
            Owner owner = preview.owners().stream().filter(item -> item.login().equals(login)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown owner"));
            List<String> ids = references(candidate, allowed);
            if (!ids.contains(owner.evidenceId())) throw new IllegalArgumentException("Owner evidence missing");
            owners.add(new OwnerCandidate(login, value(candidate, "reason", 500), ids));
        }
        return new Proposal(value(node, "summary", 1200), category, priority, value(node, "reason", 1200), references(node, allowed),
            List.copyOf(questions), List.copyOf(actions), List.copyOf(owners));
    }
    private static List<String> references(JsonNode value, Set<String> allowed) {
        var ids = new LinkedHashSet<String>();
        for (var ref : array(value, "evidenceIds", 6)) { String id = string(ref, 40); if (!allowed.contains(id)) throw new IllegalArgumentException("Unknown evidence"); ids.add(id); }
        if (ids.isEmpty()) throw new IllegalArgumentException("Evidence required");
        return List.copyOf(ids);
    }
    private static JsonNode array(JsonNode value, String field, int max) { JsonNode node = value.path(field); if (!node.isArray() || node.size() > max) throw new IllegalArgumentException("Invalid list"); return node; }
    private static String value(JsonNode value, String field, int max) { return string(value.path(field), max); }
    private static String string(JsonNode node, int max) { if (!node.isTextual() || node.asText().isBlank() || node.asText().length() > max) throw new IllegalArgumentException("Invalid text"); return IssueEvidenceText.clean(node.asText()); }
}
