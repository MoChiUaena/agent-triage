package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.development.CiModels.*;
import io.github.mochiuaena.triage.development.CiReportStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:ci-model;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.mode=MODEL", "triage.model.api-key=fixture-model-key", "triage.model.name=fixture-ci", "triage.model.timeout=5s"
})
class GithubCiModelApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "b".repeat(40);
    static final AtomicBoolean modelError = new AtomicBoolean(), invalidCitation = new AtomicBoolean(), toolReply = new AtomicBoolean(), slowModel = new AtomicBoolean();
    static final AtomicInteger modelCalls = new AtomicInteger();
    static final AtomicReference<String> modelInput = new AtomicReference<>();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("ci-model-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CiReportStore reports;
    UUID first, second;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("triage.model.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }

    @AfterAll static void stopStub() { server.stop(0); serverWorkers.shutdownNow(); }

    @BeforeEach void reset() {
        modelError.set(false); invalidCitation.set(false); toolReply.set(false); slowModel.set(false);
        modelCalls.set(0); modelInput.set(null);
        jdbc.update("DELETE FROM ci_model_analyses");
        jdbc.update("DELETE FROM ci_reports");
        jdbc.update("DELETE FROM github_repositories");
        jdbc.update("DELETE FROM model_selection");
        first = insertReport("acme/first");
        second = insertReport("acme/second");
    }

    /** Rule reports are immutable inputs for the model stage; inserted directly to keep GitHub out of scope. */
    UUID insertReport(String repository) {
        UUID repositoryId = UUID.randomUUID();
        var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
        jdbc.update("INSERT INTO github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot) VALUES(?,?,?,?,?,?)",
            repositoryId.toString(), repository.split("/")[0], repository.split("/")[1], now, now, "{}");
        Instant time = Instant.parse("2026-10-24T00:00:00Z");
        Report report = new Report(UUID.randomUUID(), repositoryId, repository, 9901, 3, HEAD, "Verify",
            new Job(777, 9901, 3, HEAD, "Build", "completed", "failure",
                List.of(new Step(1, "Compile", "success", time, time.plusSeconds(10)),
                    new Step(2, "Test", "failure", time.plusSeconds(11), time.plusSeconds(20))),
                "https://github.com/" + repository + "/actions/runs/9901/job/777"),
            "GITHUB", Instant.now(), "来自所选 GitHub 作业日志。",
            new Analysis("COMPILATION_FAILURE", "OBSERVED", "编译阶段失败", "错误片段包含编译错误或无法解析的符号。",
                List.of("查看日志提到的文件、行号和符号。"),
                List.of(new Evidence("CI-LOG-1", 10, 12, "[ERROR] cannot find symbol\nsymbol: class Inventory\nTOKEN=  \"[REDACTED]\""),
                    new Evidence("CI-LOG-2", 40, 41, "Process completed with exit code 1")),
                List.of(new Location("src/main/java/Inventory.java", 17)),
                "只扫描失败步骤“Test”的时间段。", false));
        reports.insert(report);
        return report.id();
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Development", "1");
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<Map> preview(UUID report) {
        return post("/api/development/ci/reports/" + report + "/model/previews", Map.of(), Map.class);
    }

    private ResponseEntity<Map> analyze(UUID report, Object previewId, boolean confirmed) {
        return post("/api/development/ci/reports/" + report + "/model/analyses",
            Map.of("previewId", previewId, "confirmed", confirmed), Map.class);
    }

    private String reportPayload(UUID id) {
        return jdbc.queryForObject("SELECT payload FROM ci_reports WHERE id=?", String.class, id.toString());
    }

    @Test void previewShowsExactScopeOptionAndOmissionsWithoutCallingTheModel() {
        var response = preview(first);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = response.getBody();
        assertThat(body.get("reportId")).isEqualTo(first.toString());
        assertThat(body).containsEntry("runId", 9901).containsEntry("attempt", 3).containsEntry("headSha", HEAD);
        assertThat(((Map) body.get("job")).get("id")).isEqualTo(777);
        assertThat(body).containsEntry("ruleCategory", "COMPILATION_FAILURE").containsEntry("ruleTitle", "编译阶段失败");
        assertThat(((List<Map<?, ?>>) body.get("evidence")).stream().map(e -> e.get("id").toString())).containsExactly("CI-LOG-1", "CI-LOG-2");
        assertThat(body.get("omissions").toString()).contains("完整日志", "源码");
        Map model = (Map) body.get("model");
        assertThat(model).containsEntry("available", true).containsEntry("modelName", "fixture-ci");
        assertThat(body.get("expiresAt")).isNotNull();
        assertThat(modelCalls).hasValue(0);
        assertThat(http.getForEntity("/api/development/ci/reports/" + second + "/model/previews", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        var option = http.getForEntity("/api/development/ci/model-option", Map.class).getBody();
        assertThat(option).containsEntry("available", true).containsEntry("modelName", "fixture-ci");
        assertThat(http.getForEntity("/api/development/ci/reports/" + UUID.randomUUID() + "/model/previews", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(http.getForEntity("/api/development/ci/model-analyses/" + UUID.randomUUID(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void confirmedAnalysisKeepsTheRuleReportFrozenAndValidatesCitationsAndUsage() {
        String before = reportPayload(first);
        assertThat(analyze(first, UUID.randomUUID(), true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map body = preview(first).getBody();
        assertThat(analyze(first, body.get("id"), false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(modelCalls).hasValue(0);
        HttpHeaders anonymous = new HttpHeaders(); anonymous.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange("/api/development/ci/reports/" + first + "/model/analyses", HttpMethod.POST,
            new HttpEntity<>(Map.of("previewId", body.get("id"), "confirmed", true), anonymous), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        var created = analyze(first, body.get("id"), true);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map advice = created.getBody();
        assertThat(advice.get("reportId")).isEqualTo(first.toString());
        assertThat(advice).containsEntry("runId", 9901).containsEntry("attempt", 3).containsEntry("headSha", HEAD);
        Map suggestion = (Map) advice.get("suggestion");
        assertThat(suggestion.get("summary").toString()).isNotBlank();
        assertThat(suggestion).containsEntry("stageRelation", "MATCHES");
        assertThat(suggestion.get("knownStage").toString()).isNotBlank();
        assertThat((List<Object>) suggestion.get("evidenceIds")).containsExactly("CI-LOG-1");
        assertThat((List<?>) suggestion.get("hypotheses")).hasSize(1);
        assertThat(((Map) ((List<?>) suggestion.get("hypotheses")).getFirst()).get("evidenceIds").toString()).contains("CI-LOG-1");
        Map model = (Map) advice.get("model");
        assertThat(model).containsEntry("state", "SUCCEEDED").containsEntry("modelName", "fixture-ci");
        assertThat(((Map) model.get("usage"))).containsEntry("totalTokens", 150);
        assertThat(modelCalls).hasValue(1);
        assertThat(modelInput.get()).contains("CI-LOG-1", HEAD, "COMPILATION_FAILURE")
            .doesNotContain("fixture-model-key", "fixture-ci-key");
        assertThat(reportPayload(first)).isEqualTo(before);

        var list = http.getForEntity("/api/development/ci/reports/" + first + "/model/analyses", List.class).getBody();
        assertThat(list).hasSize(1);
        assertThat(http.getForEntity("/api/development/ci/model-analyses/" + advice.get("id"), Map.class).getBody())
            .containsEntry("id", advice.get("id"));
        assertThat((List<?>) http.getForEntity("/api/development/ci/reports/" + second + "/model/analyses", List.class).getBody()).isEmpty();
    }

    @Test void invalidModelOutputIsDroppedAndRecordedWithoutTouchingTheRuleReport() {
        String before = reportPayload(first);
        Map body = preview(first).getBody();
        invalidCitation.set(true);
        var invented = analyze(first, body.get("id"), true);
        assertThat(invented.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) invented.getBody().get("model")).get("state")).isEqualTo("INVALID_OUTPUT");
        assertThat(invented.getBody().get("suggestion")).isNull();
        assertThat(modelCalls).hasValue(1);
        assertThat(reportPayload(first)).isEqualTo(before);

        Map again = preview(first).getBody();
        toolReply.set(true);
        var tools = analyze(first, again.get("id"), true);
        assertThat(((Map) tools.getBody().get("model")).get("state")).isEqualTo("INVALID_OUTPUT");
        assertThat(modelCalls).hasValue(2);
        assertThat(reportPayload(first)).isEqualTo(before);
    }

    @Test void stalePreviewDifferentReportOrChangedModelIsRejected() {
        Map body = preview(first).getBody();
        assertThat(analyze(second, body.get("id"), true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
        assertThat(analyze(first, UUID.randomUUID(), true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity("/api/development/ci/reports/" + UUID.randomUUID() + "/model/analyses", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("INSERT INTO model_selection(id, mode, provider_id) VALUES(1, 'DEMO', NULL)");
        assertThat(analyze(first, body.get("id"), true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
    }

    @Test void modelFailureAndTimeoutRecordStateAndKeepTheRuleReportWithoutRetrying() {
        String before = reportPayload(first);
        modelError.set(true);
        var failed = analyze(first, preview(first).getBody().get("id"), true);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) failed.getBody().get("model")).get("state")).isEqualTo("FAILED");
        assertThat(((Map) failed.getBody().get("model")).get("message").toString()).doesNotContain("private error body");
        assertThat(failed.getBody().get("suggestion")).isNull();
        assertThat(modelCalls).hasValue(1);
        assertThat(reportPayload(first)).isEqualTo(before);

        slowModel.set(true);
        var timedOut = analyze(first, preview(first).getBody().get("id"), true);
        assertThat(((Map) timedOut.getBody().get("model")).get("state")).isIn("TIMED_OUT", "FAILED");
        assertThat(modelCalls).hasValue(2);
        assertThat(reportPayload(first)).isEqualTo(before);
    }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.endsWith("/chat/completions")) {
                    modelCalls.incrementAndGet(); modelInput.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    if (slowModel.get()) try { Thread.sleep(6500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    var content = Map.of("summary", "编译失败集中在 Inventory 符号解析；日志不足以确认根因。", "knownStage",
                        "与规则报告的编译阶段失败一致，日志块未提供更多阶段信息。", "stageRelation", "MATCHES",
                        "hypotheses", List.of(Map.of("text", "Inventory 类可能尚未随该提交生成或依赖缺失。", "evidenceIds", List.of(invalidCitation.get() ? "invented" : "CI-LOG-1"))),
                        "verifications", List.of(Map.of("text", "核对同一提交本机构建是否复现同一符号错误。", "evidenceIds", List.of("CI-LOG-1"))),
                        "evidenceIds", List.of("CI-LOG-1"));
                    data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-ci", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(content)))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                    if (modelError.get()) { status = 500; data = Map.of("error", "private error body"); }
                    if (toolReply.get()) data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-ci", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls",
                            List.of(Map.of("id", "call", "type", "function", "function", Map.of("name", "execute", "arguments", "{}")))))));
                } else { status = 404; data = Map.of(); }
                byte[] bytes = JSON.writeValueAsBytes(data); exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
