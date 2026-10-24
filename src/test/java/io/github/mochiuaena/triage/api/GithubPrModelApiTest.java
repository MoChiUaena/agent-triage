package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:pr-model;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-pr-token", "triage.mode=MODEL", "triage.model.api-key=fixture-model-key",
    "triage.model.name=fixture-pr", "triage.model.timeout=5s"
})
class GithubPrModelApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), MERGE_BASE = "c".repeat(40);
    static final AtomicBoolean changed = new AtomicBoolean(), modelError = new AtomicBoolean(), slowModel = new AtomicBoolean(), toolReply = new AtomicBoolean();
    static final AtomicBoolean invalidFile = new AtomicBoolean(), invalidLine = new AtomicBoolean(), wrongSha = new AtomicBoolean(), badConfidence = new AtomicBoolean();
    static final AtomicInteger pullReads = new AtomicInteger(), modelCalls = new AtomicInteger();
    static final AtomicReference<String> modelInput = new AtomicReference<>();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("pr-model-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    String repo, report;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        properties.add("triage.model.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }

    @AfterAll static void stop() { server.stop(0); serverWorkers.shutdownNow(); }

    @BeforeEach void reset() {
        changed.set(false); modelError.set(false); slowModel.set(false); toolReply.set(false);
        invalidFile.set(false); invalidLine.set(false); wrongSha.set(false); badConfidence.set(false);
        pullReads.set(0); modelCalls.set(0); modelInput.set(null);
        jdbc.update("DELETE FROM pr_model_analyses");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM github_repositories");
        repo = register();
        report = create();
    }

    String register() {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
    }

    String create() {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/pr/reports",
            new HttpEntity<>(Map.of("number", 7, "headSha", HEAD, "baseSha", BASE), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers()), type);
    }

    private ResponseEntity<Map> preview() {
        return post("/api/development/pr/reports/" + report + "/model/previews", Map.of(), Map.class);
    }

    private ResponseEntity<Map> analyze(Map body) {
        return post("/api/development/pr/reports/" + report + "/model/analyses", body, Map.class);
    }

    private String reportPayload() {
        return jdbc.queryForObject("SELECT payload FROM pr_reports WHERE id=?", String.class, report);
    }

    @Test void previewShowsDiffScopeCiIssuesAndOmissionsWithoutCallingTheModel() {
        var response = preview();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = response.getBody();
        assertThat(body.get("reportId")).isEqualTo(report);
        assertThat(body).containsEntry("number", 7).containsEntry("baseSha", BASE).containsEntry("headSha", HEAD)
            .containsEntry("mergeBaseSha", MERGE_BASE);
        assertThat((List<?>) body.get("files")).hasSize(1);
        Map file = (Map) ((List<?>) body.get("files")).getFirst();
        assertThat(file.get("file")).isEqualTo("src/main/java/Security.java");
        assertThat(file.get("patch").toString()).contains("permitAll").contains("[REDACTED]").doesNotContain("ghp_");
        assertThat(file.get("newLines").toString()).isEqualTo("[{start=10, count=2}]");
        assertThat(file.get("oldLines").toString()).isEqualTo("[{start=10, count=2}]");
        assertThat((List<?>) body.get("findings")).isNotEmpty();
        assertThat(((Map<?, ?>) body.get("ci")).get("state")).isEqualTo("OBSERVED_SUCCESS");
        assertThat((List<?>) body.get("issues")).hasSize(1);
        assertThat(body.get("omissions").toString()).contains("补丁", "codeConfirmed");
        Map model = (Map) body.get("model");
        assertThat(model).containsEntry("available", true).containsEntry("modelName", "fixture-pr");
        assertThat(modelCalls).hasValue(0);
        assertThat(http.getForEntity("/api/development/pr/model-option", Map.class).getBody())
            .containsEntry("available", true).containsEntry("modelName", "fixture-pr");
        assertThat(post("/api/development/pr/reports/" + UUID.randomUUID() + "/model/previews", Map.of(), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void analysisRequiresBothConfirmationsAndNeverTouchesTheRuleReport() {
        String before = reportPayload();
        Map body = preview().getBody();
        assertThat(analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", false)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(analyze(Map.of("previewId", body.get("id"), "confirmed", false, "codeConfirmed", true)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(analyze(Map.of("previewId", UUID.randomUUID().toString(), "confirmed", true, "codeConfirmed", true)).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
        HttpHeaders anonymous = new HttpHeaders(); anonymous.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange("/api/development/pr/reports/" + report + "/model/analyses", HttpMethod.POST,
            new HttpEntity<>(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true), anonymous), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var created = analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map advice = created.getBody();
        assertThat(advice.get("reportId")).isEqualTo(report);
        assertThat(advice).containsEntry("number", 7).containsEntry("headSha", HEAD).containsEntry("mergeBaseSha", MERGE_BASE);
        Map suggestion = (Map) advice.get("suggestion");
        assertThat(suggestion.get("summary").toString()).isNotBlank();
        assertThat(suggestion).containsEntry("confidence", "MEDIUM").containsEntry("reviewedSha", HEAD);
        assertThat(suggestion.get("intent").toString()).isNotBlank();
        Map risk = (Map) ((List<?>) suggestion.get("risks")).getFirst();
        assertThat(((List<?>) risk.get("references")).getFirst().toString()).contains("Security.java").contains("11");
        Map model = (Map) advice.get("model");
        assertThat(model).containsEntry("state", "SUCCEEDED").containsEntry("modelName", "fixture-pr");
        assertThat((Map) model.get("usage")).containsEntry("totalTokens", 150);
        assertThat(modelCalls).hasValue(1);
        assertThat(modelInput.get()).contains("Security.java", HEAD, "permitAll").doesNotContain("ghp_", "fixture-model-key");
        assertThat(reportPayload()).isEqualTo(before);
        assertThat((List<?>) http.getForEntity("/api/development/pr/reports/" + report + "/model/analyses", List.class).getBody()).hasSize(1);
        assertThat(http.getForEntity("/api/development/pr/model-analyses/" + advice.get("id"), Map.class).getBody())
            .containsEntry("id", advice.get("id"));
    }

    @Test void invalidCitationsShaConfidenceOrToolCallsAreDroppedWithoutTouchingTheRuleReport() {
        String before = reportPayload();
        for (AtomicBoolean flag : List.of(invalidFile, invalidLine, wrongSha, badConfidence, toolReply)) {
            Map body = preview().getBody();
            flag.set(true);
            var response = analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(((Map) response.getBody().get("model")).get("state")).isEqualTo("INVALID_OUTPUT");
            assertThat(response.getBody().get("suggestion")).isNull();
            assertThat(reportPayload()).isEqualTo(before);
            flag.set(false);
        }
        assertThat(modelCalls).hasValue(5);
    }

    @Test void updatedPrOrRemovedRegistrationInvalidatesPreviewAndAnalysis() {
        Map body = preview().getBody();
        changed.set(true);
        assertThat(analyze(Map.of("previewId", body.get("id"), "confirmed", true, "codeConfirmed", true)).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
        assertThat(preview().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        changed.set(false);
        Map again = preview().getBody();
        http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(headers()), String.class);
        assertThat(analyze(Map.of("previewId", again.get("id"), "confirmed", true, "codeConfirmed", true)).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(modelCalls).hasValue(0);
    }

    @Test void modelFailureKeepsTheRuleReportAndDoesNotRetry() {
        String before = reportPayload();
        modelError.set(true);
        var failed = analyze(Map.of("previewId", preview().getBody().get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) failed.getBody().get("model")).get("state")).isEqualTo("FAILED");
        assertThat(((Map) failed.getBody().get("model")).get("message").toString()).doesNotContain("private error body");
        assertThat(failed.getBody().get("suggestion")).isNull();
        assertThat(modelCalls).hasValue(1);
        assertThat(reportPayload()).isEqualTo(before);
        slowModel.set(true);
        var timedOut = analyze(Map.of("previewId", preview().getBody().get("id"), "confirmed", true, "codeConfirmed", true));
        assertThat(((Map) timedOut.getBody().get("model")).get("state")).isIn("TIMED_OUT", "FAILED");
        assertThat(modelCalls).hasValue(2);
        assertThat(reportPayload()).isEqualTo(before);
    }

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.endsWith("/chat/completions")) {
                    modelCalls.incrementAndGet(); modelInput.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (slowModel.get()) try { Thread.sleep(6500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    var ref = Map.of("file", invalidFile.get() ? "src/main/java/Other.java" : "src/main/java/Security.java",
                        "line", invalidLine.get() ? 9999 : 11, "side", "NEW");
                    var gapRef = Map.of("file", "src/main/java/Security.java", "line", 10, "side", "NEW");
                    var content = Map.of("summary", "改动将固定认证替换为 permitAll；新增行为缺少测试。", "intent",
                        "替换鉴权实现，意图需与 Issue #2 核对。", "confidence", badConfidence.get() ? "CERTAIN" : "MEDIUM",
                        "reviewedSha", wrongSha.get() ? "e".repeat(40) : HEAD,
                        "risks", List.of(Map.of("text", "权限被完全放开，需确认是否预期。", "references", List.of(ref))),
                        "testGaps", List.of(Map.of("text", "缺少该路径的回归测试。", "references", List.of(gapRef))),
                        "references", List.of(ref));
                    data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-pr", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(content)))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                    if (modelError.get()) { status = 500; data = Map.of("error", "private error body"); }
                    if (toolReply.get()) data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-pr", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls",
                            List.of(Map.of("id", "call", "type", "function", "function", Map.of("name", "execute", "arguments", "{}")))))));
                } else if (path.endsWith("/pulls/7")) {
                    int read = pullReads.incrementAndGet();
                    data = Map.of("number", 7, "title", "Update access", "body", "Fixes #2", "state", "open", "draft", false,
                        "changed_files", 1, "additions", 1, "deletions", 1,
                        "base", Map.of("sha", BASE, "repo", Map.of("full_name", "acme/service")),
                        "head", Map.of("sha", changed.get() && read > 1 ? "d".repeat(40) : HEAD, "repo", Map.of("full_name", "acme/service")));
                } else if (path.equals("/repos/acme/service/compare/" + BASE + "..." + HEAD)) {
                    data = Map.of("base_commit", Map.of("sha", BASE), "merge_base_commit", Map.of("sha", MERGE_BASE),
                        "files", List.of(Map.of("filename", "src/main/java/Security.java", "status", "modified",
                            "additions", 1, "deletions", 1, "patch",
                            "@@ -10,2 +10,2 @@\n context;\n-authenticated();\n+http.permitAll(); // token=ghp_" + "s".repeat(40))));
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/check-runs")) {
                    data = Map.of("total_count", 1, "check_runs", List.of(Map.of("id", 81, "name", "Verify", "status",
                        "completed", "conclusion", "success", "head_sha", HEAD)));
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/status")) {
                    data = Map.of("sha", HEAD, "total_count", 0, "statuses", List.of());
                } else if (path.endsWith("/issues/2")) {
                    data = Map.of("number", 2, "title", "Access behavior", "state", "open");
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
