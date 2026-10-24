package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.development.PrModelModels.*;
import io.github.mochiuaena.triage.development.PrModelModels.Preview;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Rejects suggestions citing files, lines or a SHA outside the exact sent scope: files must be in
 * the preview, line numbers must fall in the sent hunks, and reviewedSha must equal the head SHA.
 */
@Component
public class PrModelSuggestionParser {
    private static final List<String> CONFIDENCE = List.of("HIGH", "MEDIUM", "LOW");
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{40}");
    private final ObjectMapper json;

    public PrModelSuggestionParser(ObjectMapper json) { this.json = json; }

    Suggestion parse(String text, Preview preview) {
        if (text == null || text.length() > 16_000) throw new IllegalArgumentException("Invalid model output");
        text = text.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        JsonNode node;
        try { node = json.readTree(text); } catch (java.io.IOException error) { throw new IllegalArgumentException("Invalid model JSON"); }
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing suggestion");
        String confidence = value(node, "confidence", 20);
        if (!CONFIDENCE.contains(confidence)) throw new IllegalArgumentException("Unknown confidence");
        String reviewedSha = value(node, "reviewedSha", 40).toLowerCase(Locale.ROOT);
        if (!SHA.matcher(reviewedSha).matches() || !reviewedSha.equals(preview.headSha().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Wrong reviewed SHA");
        Map<String, SendFile> sent = new HashMap<>();
        for (SendFile file : preview.files()) sent.put(file.file(), file);
        Map<String, PrSourceContext.Entry> context = new HashMap<>();
        for (PrSourceContext.Entry entry : preview.context())
            if (PrSourceContext.MATCHED.equals(entry.state())) context.put(entry.file(), entry);
        var risks = new ArrayList<Change>();
        for (JsonNode item : array(node, "risks", 6)) risks.add(new Change(value(item, "text", 600), references(item, sent, context)));
        var gaps = new ArrayList<Change>();
        for (JsonNode item : array(node, "testGaps", 8)) gaps.add(new Change(value(item, "text", 600), references(item, sent, context)));
        return new Suggestion(value(node, "summary", 1200), value(node, "intent", 1200), confidence, reviewedSha,
            List.copyOf(risks), List.copyOf(gaps), references(node, sent, context));
    }

    private List<FileLine> references(JsonNode value, Map<String, SendFile> sent, Map<String, PrSourceContext.Entry> context) {
        var lines = new LinkedHashSet<FileLine>();
        for (var ref : array(value, "references", 8)) {
            String file = value(ref, "file", 1024);
            int line = ref.path("line").asInt(-1);
            String side = ref.hasNonNull("side") ? value(ref, "side", 8) : "NEW";
            if (!Set.of("NEW", "OLD", "CONTEXT").contains(side)) throw new IllegalArgumentException("Unknown side");
            if ("CONTEXT".equals(side)) {
                PrSourceContext.Entry entry = context.get(file);
                if (entry == null || line < 1 || entry.symbols().stream().noneMatch(symbol ->
                        line >= symbol.startLine() && line <= symbol.endLine()))
                    throw new IllegalArgumentException("Context citation outside the sent excerpts");
                lines.add(new FileLine(file, line, side));
                continue;
            }
            SendFile scope = sent.get(file);
            if (scope == null) throw new IllegalArgumentException("File outside the sent diff");
            List<LineRange> ranges = "OLD".equals(side) ? scope.oldLines() : scope.newLines();
            if (line < 1 || line > 100_000 || ranges.stream().noneMatch(range -> line >= range.start() && line < range.start() + range.count()))
                throw new IllegalArgumentException("Line outside the sent hunks");
            lines.add(new FileLine(file, line, side));
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("References required");
        return List.copyOf(lines);
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
