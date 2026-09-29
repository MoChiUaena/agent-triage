package io.github.mochiuaena.triage.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;
import static org.assertj.core.api.Assertions.*;

class SourceCallGraphTest {
    @TempDir Path root;
    private void write(String path, String text) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    private Index index() throws Exception { return new JavaSourceIndexer().index(root.toRealPath()); }
    private Symbol method(Index index, String type, String name) {
        return index.files().stream().flatMap(file -> file.symbols().stream()).filter(value -> value.className().equals(type) && value.method().equals(name)).findFirst().orElseThrow();
    }
    private CallGraph graph(Index index, String type, String name) {
        String id = method(index, type, name).id();
        return new SourceCallGraph(index).build(List.of(id), (identity, focus) -> {
            Symbol symbol = index.files().stream().flatMap(file -> file.symbols().stream()).filter(value -> value.id().equals(identity)).findFirst().orElseThrow();
            return new Excerpt(identity, symbol.path(), symbol.fileHash(), symbol.className(), symbol.method(), symbol.route(), symbol.httpMethods(), symbol.calls(), symbol.startLine(), symbol.endLine(), "verified-test-reference");
        }, () -> {});
    }
    private void helpdesk() throws Exception {
        write("helpdesk/Controller.java", """
            package helpdesk;
            class Controller {
                private final TicketService service;
                @GetMapping("/api/tickets/{id}")
                Object ticket(String id) { return service.find(id); }
            }
            """);
        write("helpdesk/TicketService.java", "package helpdesk; interface TicketService { Object find(String id); }");
        write("helpdesk/TicketServiceImpl.java", "package helpdesk; class TicketServiceImpl implements TicketService { Gateway gateway; public Object find(String id) { return gateway.lookup(id); } }");
        write("helpdesk/Gateway.java", """
            package helpdesk;
            import org.springframework.web.client.RestClient;
            class Gateway {
                RestClient assignments;
                Object lookup(String id) { return assignments.get().uri("/assignments/{id}", id).retrieve().body(Object.class); }
            }
            """);
    }
    @Test void followsUniqueInterfaceCandidateToGatewayAndOneHttpBoundary() throws Exception {
        helpdesk(); Index index = index(); CallGraph result = graph(index, "helpdesk.Controller", "ticket");
        assertThat(result.nodes()).extracting(value -> value.excerpt().className()).containsExactly("helpdesk.Controller", "helpdesk.TicketServiceImpl", "helpdesk.Gateway");
        assertThat(result.edges()).extracting(CallEdge::resolution).containsExactly("CANDIDATE", "RESOLVED", "BOUNDARY");
        assertThat(result.edges().getFirst().message()).contains("运行时注入待确认");
        assertThat(result.edges().getLast().kind()).isEqualTo("HTTP");
        assertThat(result.edges().getLast().call()).isEqualTo("assignments.get().uri().retrieve().body");
        assertThat(result.edges().getLast().line()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();
    }
    @Test void severalInterfaceImplementationsStayAmbiguousAndDoNotPickTheFirst() throws Exception {
        helpdesk(); write("helpdesk/Other.java", "package helpdesk; class Other implements TicketService { public Object find(String id) { return null; } }");
        CallGraph result = graph(index(), "helpdesk.Controller", "ticket");
        assertThat(result.edges().getFirst().resolution()).isEqualTo("AMBIGUOUS");
        assertThat(result.edges().getFirst().targetIds()).hasSize(2);
        assertThat(result.nodes()).extracting(value -> value.excerpt().className()).contains("helpdesk.Other", "helpdesk.TicketServiceImpl");
    }
    @Test void knownArgumentsMatchOverloadsAndUnknownExpressionsKeepAllCandidates() throws Exception {
        write("Overloads.java", """
            class Overloads {
                Helper helper;
                void exact(int value) { helper.read(value); }
                void unknown() { helper.read(factory()); }
                Object factory() { return null; }
            }
            class Helper { void read(int id) {} void read(String id) {} }
            """);
        Index index = index();
        var exact = graph(index, "Overloads", "exact");
        assertThat(exact.edges().getFirst().resolution()).isEqualTo("RESOLVED");
        String target = exact.edges().getFirst().targetIds().getFirst();
        assertThat(index.files().getFirst().symbols().stream().filter(value -> value.id().equals(target)).findFirst().orElseThrow().signature()).isEqualTo("read(int)");
        var unknown = graph(index, "Overloads", "unknown");
        assertThat(unknown.edges().getFirst().resolution()).isEqualTo("AMBIGUOUS");
        assertThat(unknown.edges().getFirst().targetIds()).hasSize(2);
    }
    @Test void localShadowingLambdaAndFluentReturnsCannotInventFieldCalls() throws Exception {
        write("Scopes.java", """
            class Scopes {
                Gateway gateway;
                void shadow() { Object gateway = unknown(); gateway.lookup(); }
                void inferred() { var gateway = new Other(); gateway.lookup(); }
                void callback() { executor.execute(() -> gateway.lookup()); }
                void reference() { executor.execute(gateway::lookup); }
                void chain() { gateway.lookup().lookup(); }
                Object unknown() { return null; }
            }
            class Gateway { Object lookup() { return null; } }
            class Other { Object lookup() { return null; } }
            """);
        Index index = index();
        assertThat(graph(index, "Scopes", "shadow").edges().stream().filter(value -> value.call().equals("gateway.lookup"))).allMatch(value -> value.targetIds().isEmpty());
        var inferred = graph(index, "Scopes", "inferred");
        assertThat(inferred.nodes()).extracting(value -> value.excerpt().className()).contains("Other").doesNotContain("Gateway");
        var callback = graph(index, "Scopes", "callback");
        assertThat(callback.edges()).anyMatch(value -> value.kind().equals("DEFERRED") && value.targetIds().isEmpty());
        assertThat(graph(index, "Scopes", "reference").edges()).anyMatch(value -> value.kind().equals("DEFERRED") && value.call().equals("gateway::lookup") && value.targetIds().isEmpty());
        var chain = graph(index, "Scopes", "chain");
        assertThat(chain.edges().getFirst().resolution()).isEqualTo("UNRESOLVED");
        assertThat(chain.edges().getFirst().targetIds()).isEmpty();
    }
    @Test void importsDistinguishClassesWithTheSameSimpleName() throws Exception {
        write("entry/Entry.java", "package entry; import first.Gateway; class Entry { Gateway gateway; void run() { gateway.read(); } }");
        write("first/Gateway.java", "package first; class Gateway { void read() {} }");
        write("second/Gateway.java", "package second; class Gateway { void read() {} }");
        CallGraph result = graph(index(), "entry.Entry", "run");
        assertThat(result.nodes()).extracting(value -> value.excerpt().className()).containsExactly("entry.Entry", "first.Gateway");
    }
    @Test void sourceClassNamedRestClientIsNotClassifiedAsHttpAndSuperRemainsUnresolved() throws Exception {
        write("Local.java", """
            class Local { RestClient client; void run() { client.retrieve(); } void parent() { super.run(); } }
            class RestClient { void retrieve() {} }
            """);
        Index index = index();
        assertThat(graph(index, "Local", "run").edges().getFirst().kind()).isEqualTo("PROJECT");
        assertThat(graph(index, "Local", "parent").edges().getFirst().targetIds()).isEmpty();
    }
    @Test void cyclesTerminateAndDepthLimitsAreVisible() throws Exception {
        write("Cycle.java", "class Cycle { void run() { run(); } }");
        CallGraph cycle = graph(index(), "Cycle", "run");
        assertThat(cycle.nodes()).hasSize(1); assertThat(cycle.edges()).hasSize(1);
        for (int i = 0; i < 9; i++) write("Step" + i + ".java", "class Step" + i + " { Step" + (i + 1) + " next; void run() { next.run(); } }");
        CallGraph bounded = graph(index(), "Step0", "run");
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.nodes()).hasSize(SourceCallGraph.MAX_DEPTH + 1);
        assertThat(bounded.nodes()).allMatch(value -> value.depth() <= SourceCallGraph.MAX_DEPTH);
    }
    @Test void jdbcBoundariesKeepConnectionAcquisitionSeparateFromQueryExecution() throws Exception {
        write("Database.java", """
            import javax.sql.DataSource;
            import java.sql.Connection;
            class Database {
                DataSource source;
                void run() { Connection connection = source.getConnection(); connection.prepareStatement("select 1").executeQuery(); }
            }
            """);
        CallGraph result = graph(index(), "Database", "run");
        assertThat(result.edges()).extracting(CallEdge::kind).containsExactly("DATABASE_ACQUIRE", "DATABASE_QUERY");
        assertThat(result.edges().toString()).doesNotContain("select 1");
        var metrics = new Evidence("m", "read_service_metrics", "指标", "窗口", Map.of("requestCount", 2, "observationType", "DATABASE_POOL", "databasePool", Map.of("acquisitionTimeoutCount", 0, "queryErrorCount", 1)));
        var linked = SourceEvidenceLinks.attach(result, List.of(metrics), false);
        assertThat(linked.evidenceLinks()).extracting(EvidenceLink::kind).containsExactly("DB_QUERY_ERROR");
        assertThat(linked.evidenceLinks().getFirst().edgeIds()).containsExactly(result.edges().get(1).id());
    }
    @Test void perMethodCallLimitIsReportedRatherThanLookingLikeACompleteGraph() throws Exception {
        write("Bounded.java", "class Bounded { void run() { " + "System.nanoTime();".repeat(35) + " } }");
        CallGraph result = graph(index(), "Bounded", "run");
        assertThat(result.edges()).hasSize(30);
        assertThat(result.truncated()).isTrue();
    }
    @Test void observedSignatureCannotMatchAnIncompatibleSourceOverload() throws Exception {
        write("Entry.java", "class Entry { void read(int id) {} }");
        var observed = new io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint("fixture", "GET", "/api/entry", "Entry", "read", List.of("java.lang.String"), "MVC_SELECTED");
        var result = new SourceCallGraph(index()).endpoint(new io.github.mochiuaena.triage.domain.TriageModel.EndpointSummary(observed, 1, 0, 10, 0));
        assertThat(result.state()).isEqualTo("NO_MATCH"); assertThat(result.sourceIds()).isEmpty();
    }
    @Test void evidenceIsLinkedByCategoryWithoutClaimingExecutedMethodsAndOldIndexesAskForReindex() throws Exception {
        helpdesk(); Index index = index(); CallGraph graph = graph(index, "helpdesk.Controller", "ticket");
        var metrics = new Evidence("runtime-metrics", "read_service_metrics", "指标", "窗口", Map.of("requestCount", 2, "timeoutCount", 1));
        var logs = new Evidence("runtime-logs", "query_error_logs", "日志", "窗口", Map.of("entries", List.of()));
        CallGraph linked = SourceEvidenceLinks.attach(graph, List.of(metrics, logs), false);
        assertThat(linked.evidenceLinks().getFirst().edgeIds()).containsExactly(graph.edges().getLast().id());
        assertThat(linked.evidenceLinks().getFirst().evidenceIds()).containsExactly("runtime-metrics", "runtime-logs");
        assertThat(linked.evidenceLinks().getFirst().message()).contains("没有调用级轨迹");
        assertThat(SourceEvidenceLinks.attach(graph, List.of(metrics), true).evidenceLinks().getFirst().message()).startsWith("合成观测：");
        var json = new ObjectMapper().findAndRegisterModules();
        var legacy = json.valueToTree(index); ((com.fasterxml.jackson.databind.node.ObjectNode) legacy).remove("formatVersion");
        Index old = json.treeToValue(legacy, Index.class);
        assertThat(new SourceCallGraph(old).build(List.of(), (id, focus) -> { throw new AssertionError(); }, () -> {}).state()).isEqualTo("REINDEX_REQUIRED");
    }
}
