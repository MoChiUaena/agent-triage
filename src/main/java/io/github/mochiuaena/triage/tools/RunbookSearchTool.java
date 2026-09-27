package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small, transparent keyword index; no embeddings or external calls in demo mode. */
@Component
public class RunbookSearchTool implements ReadOnlyTool {
    private record Document(String id, String title, String content, List<String> keywords) {}
    private final List<Document> documents;

    public RunbookSearchTool() throws IOException {
        documents = List.of(
            load("DOC-DOWNSTREAM-TIMEOUT", "下游超时排障", "downstream-timeout.md", List.of("订单", "order", "超时", "timeout", "慢", "slow", "latency", "延迟")),
            load("DOC-HEALTHY-BASELINE", "正常状态对照", "healthy-baseline.md", List.of("订单", "order", "正常", "健康", "healthy", "baseline")),
            load("DOC-EVIDENCE-LIMITS", "证据边界与验证", "evidence-limits.md", List.of("证据", "原因", "验证", "evidence", "why"))
        );
    }

    private Document load(String id, String title, String file, List<String> keywords) throws IOException {
        String content = new ClassPathResource("runbooks/" + file).getContentAsString(StandardCharsets.UTF_8);
        if (content.length() > 2400) throw new IllegalStateException("Runbook exceeds excerpt limit: " + id);
        return new Document(id, title, content, keywords);
    }

    @Override public String name() { return "search_runbooks"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        if (query == null || query.isBlank() || query.length() > 200) throw new IllegalArgumentException("Query must contain 1..200 characters");
        String normalized = query.toLowerCase(Locale.ROOT);
        return documents.stream()
            .filter(doc -> doc.keywords().stream().anyMatch(normalized::contains))
            .limit(3)
            .map(doc -> new Evidence(doc.id() + "#v1", name(), doc.title(), doc.content(),
                Map.of("service", context.service(), "synthetic", true, "retrieval", "keyword", "version", 1)))
            .toList();
    }
}
