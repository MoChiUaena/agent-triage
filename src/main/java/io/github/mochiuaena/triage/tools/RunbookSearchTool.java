package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small, transparent keyword index; no embeddings or external calls in demo mode. */
@Component
@Order(1)
public class RunbookSearchTool implements ReadOnlyTool {
    private record Document(String id, String title, String content, List<String> keywords, int version, boolean synthetic) {}
    private final List<Document> documents;
    private final List<Document> registeredDocuments;
    private final List<Document> databaseDocuments;
    private final boolean serviceReferences;

    public RunbookSearchTool() throws IOException { this(false); }

    @Autowired
    public RunbookSearchTool(ObservationSource source) throws IOException { this(!source.synthetic()); }

    private RunbookSearchTool(boolean live) throws IOException {
        serviceReferences = live;
        documents = List.of(
            load(live ? "DOC-DOWNSTREAM-TIMEOUT#v2" : "DOC-DOWNSTREAM-TIMEOUT#v1", "下游超时排障",
                live ? "downstream-timeout-live.md" : "downstream-timeout.md",
                List.of("订单", "order", "超时", "timeout", "慢", "slow", "latency", "延迟"), live ? 2 : 1, !live),
            load(live ? "DOC-HEALTHY-BASELINE#v2" : "DOC-HEALTHY-BASELINE#v1", "正常状态对照",
                live ? "healthy-baseline-live.md" : "healthy-baseline.md",
                List.of("订单", "order", "正常", "健康", "healthy", "baseline"), live ? 2 : 1, !live),
            load("DOC-EVIDENCE-LIMITS#v1", "证据边界与验证", "evidence-limits.md",
                List.of("证据", "原因", "验证", "evidence", "why"), 1, false)
        );
        registeredDocuments = List.of(
            load("DOC-DOWNSTREAM-TIMEOUT#v3", "下游超时排障", "downstream-timeout-registered.md",
                List.of("超时", "timeout", "慢", "slow", "latency", "延迟"), 3, false),
            load("DOC-HEALTHY-BASELINE#v3", "无超时窗口对照", "healthy-baseline-registered.md",
                List.of("正常", "健康", "healthy", "baseline"), 3, false), documents.get(2));
        databaseDocuments = List.of(
            load("DOC-DB-POOL-EXHAUSTION#v1", "连接池耗尽排查", "db-pool-exhaustion.md", List.of("数据库", "连接池", "超时", "database", "pool", "timeout"), 1, false),
            load("DOC-DB-POOL-BASELINE#v1", "数据库窗口对照", "db-pool-baseline.md", List.of("数据库", "正常", "慢", "sql", "database", "baseline"), 1, false),
            load("DOC-DB-SQL-EXECUTION-FAILURE#v1", "SQL 执行失败", "db-sql-execution-failure.md", List.of("数据库", "SQL", "sql", "执行", "失败", "database", "query"), 1, false),
            documents.get(2));
    }

    private Document load(String id, String title, String file, List<String> keywords, int version, boolean synthetic) throws IOException {
        String content = new ClassPathResource("runbooks/" + file).getContentAsString(StandardCharsets.UTF_8);
        if (content.length() > 2400) throw new IllegalStateException("Runbook exceeds excerpt limit: " + id);
        return new Document(id, title, content, keywords, version, synthetic);
    }

    @Override public String name() { return "search_runbooks"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        if (query == null || query.isBlank() || query.length() > 200) throw new IllegalArgumentException("Query must contain 1..200 characters");
        String normalized = query.toLowerCase(Locale.ROOT);
        List<Document> applicable = context.target() != null && context.target().protocol() == ServiceRegistry.Protocol.OBSERVATIONS_V1
            ? registeredDocuments : documents;
        if (context.target() != null && context.target().protocol() == ServiceRegistry.Protocol.DATABASE_V2) applicable = databaseDocuments;
        return applicable.stream()
            .filter(doc -> matches(doc, normalized) || serviceReferences && coreRule(doc))
            .limit(3)
            .map(doc -> new Evidence(doc.id(), name(), doc.title(), doc.content(),
                Map.of("service", context.service(), "synthetic", doc.synthetic(),
                    "retrieval", serviceReferences ? "keyword+service-reference" : "keyword", "version", doc.version(),
                    "queryMatched", matches(doc, normalized), "serviceReference", serviceReferences && coreRule(doc))))
            .toList();
    }

    private boolean matches(Document doc, String query) { return doc.keywords().stream().anyMatch(query::contains); }
    private boolean coreRule(Document doc) {
        return doc.id().startsWith("DOC-DOWNSTREAM-TIMEOUT#") || doc.id().startsWith("DOC-HEALTHY-BASELINE#")
            || doc.id().startsWith("DOC-DB-POOL-") || doc.id().startsWith("DOC-DB-SQL-");
    }
}
