package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:pr-context;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-pr-token", "triage.mode=MODEL", "triage.model.api-key=fixture-model-key",
    "triage.model.name=fixture-pr", "triage.model.timeout=5s"
})
class GithubPrSourceContextApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), MERGE_BASE = "c".repeat(40);
    static final String ORDER_PATCH = "@@ -10,3 +10,4 @@\n         existing();\n-        authenticated();\n+        validated();\n+        audit();\n     }";
    static final String REPO_PATCH = "@@ -1,2 +1,3 @@\n package com.example;\n interface OrderRepo {\n+    void archive(Order order);";
    static final String MISSING_PATCH = "@@ -1,2 +1,2 @@\n-class Gone;\n+class StillGone;";
    static final AtomicBoolean modelError = new AtomicBoolean(), invalidContextLine = new AtomicBoolean();
    static final AtomicInteger modelCalls = new AtomicInteger();
    static final AtomicReference<String> modelInput = new AtomicReference<>();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("pr-context-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    String repo, report, project;
    long revision;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        properties.add("triage.model.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }

    @AfterAll static void stop() { server.stop(0); serverWorkers.shutdownNow(); }

    @BeforeEach void reset() throws Exception {
        modelError.set(false); invalidContextLine.set(false);
        modelCalls.set(0); modelInput.set(null);
        jdbc.update("DELETE FROM pr_model_analyses");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM source_projects");
        jdbc.update("DELETE FROM github_repositories");
        writeSources(false);
        repo = registerRepository();
        report = create();
        project = registerSource();
    }

    void writeSources(boolean drifted) throws Exception {
        Files.createDirectories(directory.resolve("src/main/java"));
        List<String> order = new ArrayList<>(List.of(
            "package com.example;", "public class Order {", "    void prepare() {", "        track();", "    }",
            "    void submit(boolean strict) {", "        if (strict) {", "            note(\"strict\");", "        }",
            "        existing();", drifted ? "        skipped();" : "        validated();", "        audit();", "    }",
            "    private void note(String value) { }", "}"));
        Files.write(directory.resolve("src/main/java/Order.java"), String.join("\n", order).getBytes(StandardCharsets.UTF_8));
        List<String> repo = List.of("package com.example;", "interface OrderRepo {", "    void archive(Order order);",
            "    void save(Order order);", "}");
        Files.write(directory.resolve("src/main/java/OrderRepo.java"), String.join("\n", repo).getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("src/main/java/JdbcOrderRepo.java"), """
            package com.example;
            class JdbcOrderRepo implements OrderRepo {
                public void save(Order order) { }
            }
            """.stripTrailing() + "\n");
        Files.writeString(directory.resolve("src/main/java/MongoOrderRepo.java"), """
            package com.example;
            class MongoOrderRepo implements OrderRepo {
                public void save(Order order) { }
            }
            """.stripTrailing() + "\n");
        Files.writeString(directory.resolve("src/main/java/OrderService.java"), """
            package com.example;
            class OrderService {
                void run(Order order) {
                    order.submit(true);
                }
            }
            """.stripTrailing() + "\n");
    }

    String registerRepository() {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers("X-Triage-Development")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
    }

    String create() {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/pr/reports",
            new HttpEntity<>(Map.of("number", 7, "headSha", HEAD, "baseSha", BASE), headers("X-Triage-Development")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    String registerSource() {
        var response = http.postForEntity("/api/source-projects", new HttpEntity<>(Map.of("name", "订单本机项目",
            "service", "order-service", "directory", directory.toString()), headers("X-Triage-Source")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        revision = ((Number) response.getBody().get("revision")).longValue();
        assertThat(revision).isEqualTo(1L);
        return response.getBody().get("id").toString();
    }

    private <T> ResponseEntity<T> post(String path, Object body, String marker, Class<T> type) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(marker)), type);
    }

    private ResponseEntity<Map> preview(Object input) {
        return post("/api/development/pr/reports/" + report + "/model/previews", input, "X-Triage-Development", Map.class);
    }

    private ResponseEntity<Map> analyze(Map body) {
        return post("/api/development/pr/reports/" + report + "/model/analyses", body, "X-Triage-Development", Map.class);
    }

    private String reportPayload() {
        return jdbc.queryForObject("SELECT payload FROM pr_reports WHERE id=?", String.class, report);
    }

    @SuppressWarnings("unchecked")
    private Map contextEntry(Map previewBody, String file) {
        return (Map) ((List<?>) previewBody.get("context")).stream()
            .filter(item -> ((Map<?, ?>) item).get("file").equals(file)).findFirst().orElse(null);
    }

    @Test void matchedContextCarriesBoundedSymbolsCallersAndImplementationsWithVersionChecks() {
        Map without = preview(Map.of()).getBody();
        assertThat((List<?>) without.get("context")).isEmpty();
        assertThat(modelCalls).hasValue(0);

        Map body = preview(Map.of("sourceProjectId", project, "sourceRevision", revision)).getBody();
        assertThat((List<?>) body.get("context")).hasSize(3);
        Map order = contextEntry(body, "src/main/java/Order.java");
        assertThat(order).containsEntry("state", "MATCHED");
        assertThat(order.get("message").toString()).contains("变更行");
        List<Map> symbols = (List<Map>) order.get("symbols");
        assertThat(symbols.stream().anyMatch(s -> s.get("method").equals("submit"))).isTrue();
        String submitContent = symbols.stream().filter(s -> s.get("method").equals("submit")).findFirst().orElseThrow()
            .get("content").toString();
        assertThat(submitContent).contains("validated();").contains("audit();").doesNotContain("authenticated");
        List<Map> callers = (List<Map>) order.get("callers");
        assertThat(callers).hasSize(1);
        assertThat(callers.getFirst()).containsEntry("path", "src/main/java/OrderService.java")
            .containsEntry("className", "com.example.OrderService").containsEntry("state", "CANDIDATE");
        Map repoEntry = contextEntry(body, "src/main/java/OrderRepo.java");
        assertThat(repoEntry).containsEntry("state", "MATCHED");
        assertThat(repoEntry.get("implementations").toString()).contains("JdbcOrderRepo", "MongoOrderRepo");
        assertThat(repoEntry.get("message").toString()).contains("多实现");
        assertThat(contextEntry(body, "src/main/java/Gone.java")).containsEntry("state", "NOT_INDEXED");
        assertThat(body.get("omissions").toString()).contains("静态候选").contains("反射");
        assertThat(modelCalls).hasValue(0);

        var created = analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) created.getBody().get("model")).get("state")).isEqualTo("SUCCEEDED");
        assertThat(modelInput.get()).contains("validated();", "OrderService", "CONTEXT", "JdbcOrderRepo");
        assertThat(jdbc.queryForObject("SELECT payload FROM pr_reports WHERE id=?", String.class, report))
            .doesNotContain("validated();");
    }

    @Test void contextCitationsAreValidatedAgainstSentExcerptRanges() {
        Map body = preview(Map.of("sourceProjectId", project, "sourceRevision", revision)).getBody();
        var created = analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) created.getBody().get("model")).get("state")).isEqualTo("SUCCEEDED");
        assertThat(modelCalls).hasValue(1);

        Map again = preview(Map.of("sourceProjectId", project, "sourceRevision", revision)).getBody();
        invalidContextLine.set(true);
        var invalid = analyze(Map.of("previewId", again.get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(((Map) invalid.getBody().get("model")).get("state")).isEqualTo("INVALID_OUTPUT");
        assertThat(invalid.getBody().get("suggestion")).isNull();
        assertThat(modelCalls).hasValue(2);
    }

    @Test void driftedOrMissingIndexStopsUsingLocalSourceAndRevisionMismatchIsRejected() throws Exception {
        writeSources(true);
        var reindexed = http.postForEntity("/api/source-projects/" + project + "/reindex",
            new HttpEntity<>(Map.of(), headers("X-Triage-Source")), Map.class);
        assertThat(((Number) reindexed.getBody().get("revision")).longValue()).isEqualTo(2L);
        Map body = preview(Map.of("sourceProjectId", project, "sourceRevision", 2L)).getBody();
        Map order = contextEntry(body, "src/main/java/Order.java");
        assertThat(order).containsEntry("state", "VERSION_MISMATCH");
        assertThat(order.get("message").toString()).contains("变更行").contains("不一致");
        assertThat((List<?>) order.get("symbols")).isEmpty();
        assertThat((List<?>) order.get("callers")).isEmpty();

        assertThat(preview(Map.of("sourceProjectId", project, "sourceRevision", 99L)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(preview(Map.of("sourceProjectId", UUID.randomUUID().toString(), "sourceRevision", 1L)).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    static HttpHeaders headers(String marker) { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set(marker, "1"); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.endsWith("/chat/completions")) {
                    modelCalls.incrementAndGet(); modelInput.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var reference = Map.of("file", "src/main/java/Order.java",
                        "line", invalidContextLine.get() ? 1 : 11, "side", "CONTEXT");
                    var content = Map.of("summary", "变更替换鉴权调用并新增审计。", "intent", "替换鉴权实现。", "confidence", "MEDIUM",
                        "reviewedSha", HEAD, "risks", List.of(Map.of("text", "需确认审计幂等。", "references", List.of(reference))),
                        "testGaps", List.of(), "references", List.of(reference));
                    data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-pr", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(content)))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                    if (modelError.get()) { status = 500; data = Map.of("error", "private error body"); }
                } else if (path.endsWith("/pulls/7")) {
                    data = Map.of("number", 7, "title", "Order changes", "body", "Fixes #2", "state", "open", "draft", false,
                        "changed_files", 3, "additions", 5, "deletions", 2,
                        "base", Map.of("sha", BASE, "repo", Map.of("full_name", "acme/service")),
                        "head", Map.of("sha", HEAD, "repo", Map.of("full_name", "acme/service")));
                } else if (path.equals("/repos/acme/service/compare/" + BASE + "..." + HEAD)) {
                    data = Map.of("base_commit", Map.of("sha", BASE), "merge_base_commit", Map.of("sha", MERGE_BASE),
                        "files", List.of(
                            Map.of("filename", "src/main/java/Order.java", "status", "modified", "additions", 2, "deletions", 1, "patch", ORDER_PATCH),
                            Map.of("filename", "src/main/java/OrderRepo.java", "status", "modified", "additions", 1, "deletions", 0, "patch", REPO_PATCH),
                            Map.of("filename", "src/main/java/Gone.java", "status", "modified", "additions", 1, "deletions", 1, "patch", MISSING_PATCH)));
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/check-runs")) {
                    data = Map.of("total_count", 0, "check_runs", List.of());
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/status")) {
                    data = Map.of("sha", HEAD, "total_count", 0, "statuses", List.of());
                } else if (path.equals("/repos/acme/service")) {
                    data = Map.of("full_name", "acme/service", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 0);
                } else { status = 404; data = Map.of(); }
                byte[] bytes = JSON.writeValueAsBytes(data);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
