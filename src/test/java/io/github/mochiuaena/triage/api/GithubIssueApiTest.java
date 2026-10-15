package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:github-issue;DB_CLOSE_DELAY=-1", "triage.github.token=fixture-github-key",
    "triage.mode=MODEL", "triage.model.api-key=fixture-model-key", "triage.model.name=fixture-issue", "triage.model.timeout=5s"
})
class GithubIssueApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final AtomicBoolean changed = new AtomicBoolean(), invalidOwner = new AtomicBoolean(), invalidCitation = new AtomicBoolean(), modelError = new AtomicBoolean();
    static final AtomicInteger modelCalls = new AtomicInteger();
    static final AtomicBoolean longPrefix = new AtomicBoolean(), botFirst = new AtomicBoolean();
    static final AtomicBoolean slowModel = new AtomicBoolean(), toolReply = new AtomicBoolean();
    static final AtomicBoolean assignedBot = new AtomicBoolean();
    static final java.util.concurrent.ExecutorService serverWorkers = java.util.concurrent.Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("issue-fixture-", 0).factory());
    static final AtomicReference<String> modelInput = new AtomicReference<>();
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        p.add("triage.model.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }
    @BeforeEach void reset() {
        changed.set(false); invalidOwner.set(false); invalidCitation.set(false); modelError.set(false); modelCalls.set(0); modelInput.set(null);
        jdbc.update("DELETE FROM github_repositories");
        jdbc.update("DELETE FROM model_selection");
        longPrefix.set(false); botFirst.set(false);
        slowModel.set(false); toolReply.set(false);
        assignedBot.set(false);
    }
    @Test void localPreviewAndReportNeverCallTheModelAndPreserveEvidenceAndHistory() {
        String repo = register(), root = root(repo);
        assertThat(http.postForEntity(root + "/previews", Map.of("number", 2), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map preview = preview(repo, List.of());
        assertThat(preview.toString()).contains("Example.java", HEAD).doesNotContain("fixture-private-password", "fixture-github-key", "fixture-model-key");
        assertThat(modelCalls).hasValue(0);
        var response = create(repo, preview, false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map report = response.getBody();
        assertThat(report).containsEntry("source", "LOCAL_RULES");
        assertThat((Map) report.get("proposal")).containsEntry("category", "BUG");
        assertThat(modelCalls).hasValue(0);
        String id = report.get("id").toString();
        assertThat(http.getForObject("/api/development/issue/reports/" + id, Map.class)).isEqualTo(report);
        assertThat((List<?>) http.getForObject(root + "/reports", List.class)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM issue_reports WHERE id=?", String.class, id)).doesNotContain("fixture-private-password");
        assertThat(http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity("/api/development/issue/reports/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void explicitConsentUsesEnabledModelWithValidatedCitationsOwnersAndUsage() {
        String repo = register(); Map preview = preview(repo, List.of());
        var response = create(repo, preview, true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("source", "MODEL");
        assertThat(modelCalls).hasValue(1);
        assertThat(modelInput.get()).contains("ISSUE-2", "Example.java").doesNotContain("fixture-private-password", "fixture-github-key", "fixture-model-key");
        Map model = (Map) response.getBody().get("model");
        assertThat(model).containsEntry("state", "SUCCEEDED").containsEntry("modelName", "fixture-issue");
        assertThat((Map) model.get("usage")).containsEntry("totalTokens", 150);
        assertThat((List<?>) ((Map) response.getBody().get("proposal")).get("ownerCandidates")).hasSize(1);
    }
    @Test void staleIssueDoesNotDispatchAModelOrSaveAMixedSnapshot() {
        String repo = register(); Map preview = preview(repo, List.of()); changed.set(true);
        assertThat(create(repo, preview, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM issue_reports", Integer.class)).isZero();
    }
    @Test void changedProviderSelectionRequiresANewPreviewBeforeSharing() {
        String repo = register(); Map preview = preview(repo, List.of());
        var settingsHeaders = headers(); settingsHeaders.set("X-Triage-Settings", "1");
        assertThat(http.exchange("/api/settings/selection", HttpMethod.PUT, new HttpEntity<>(Map.of("mode", "DEMO"), settingsHeaders), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(create(repo, preview, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(modelCalls).hasValue(0);
        // Restore the environment selection directly; the UI selection API intentionally only accepts stored providers.
        jdbc.update("DELETE FROM model_selection");
    }
    @Test void inventedCitationsOrOwnersAreDiscardedButKnownUsageAndLocalReportRemain() {
        String repo = register(); Map preview = preview(repo, List.of()); invalidCitation.set(true);
        var first = create(repo, preview, true);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).containsEntry("source", "LOCAL_RULES");
        assertThat((Map) first.getBody().get("model")).containsEntry("state", "INVALID_OUTPUT");
        invalidCitation.set(false); invalidOwner.set(true);
        var second = create(repo, preview(repo, List.of()), true);
        assertThat(second.getBody()).containsEntry("source", "LOCAL_RULES");
        assertThat((List<?>) ((Map) second.getBody().get("proposal")).get("ownerCandidates")).isEmpty();
    }
    @Test void modelHttpFailureDoesNotEraseTheLocalEvidence() {
        String repo = register(); modelError.set(true);
        var response = create(repo, preview(repo, List.of()), true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("source", "LOCAL_RULES");
        assertThat((Map) response.getBody().get("model")).containsEntry("state", "FAILED");
        assertThat((List<?>) response.getBody().get("evidence")).isNotEmpty();
    }
    @Test void unsafeOrExcessiveSourcePathsAreRejectedBeforeGithubFileReads() {
        String repo = register();
        for (List<String> paths : List.of(List.of("../Secret.java"), List.of("https://evil/Secret.java"), List.of("a.java", "b.java", "c.java", "d.java")))
            assertThat(http.postForEntity(root(repo) + "/previews", new HttpEntity<>(Map.of("number", 2, "codePaths", paths), headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void longContextCannotClipAwayTheReportedSourceLine() {
        String repo = register(); longPrefix.set(true);
        Map preview = preview(repo, List.of());
        var code = ((List<Map>) preview.get("evidence")).stream().filter(e -> e.get("kind").equals("CODE")).findFirst().orElseThrow();
        assertThat(code.get("text").toString()).contains("execute()");
        assertThat(((Number) code.get("startLine")).intValue()).isLessThanOrEqualTo(3);
        assertThat(((Number) code.get("endLine")).intValue()).isGreaterThanOrEqualTo(3);
    }
    @Test void automationContributorsDoNotHideHumanOwnerCandidates() {
        String repo = register(); botFirst.set(true);
        Map preview = preview(repo, List.of());
        var owners = (List<Map>) preview.get("owners");
        assertThat(owners).hasSize(1); assertThat(owners.getFirst()).containsEntry("login", "alice");
    }
    @Test void modelDeadlineReturnsALocalReportWithoutRetryingOrInventingUsage() {
        String repo = register(); slowModel.set(true);
        var response = create(repo, preview(repo, List.of()), true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("source", "LOCAL_RULES");
        assertThat(((Map) response.getBody().get("model")).get("state")).isIn("TIMED_OUT", "FAILED");
        assertThat(((Map) response.getBody().get("model")).get("usage")).isNull();
        assertThat(modelCalls).hasValue(1);
    }
    @Test void modelToolRequestsAreRejectedWithoutExecutingThem() {
        String repo = register(); toolReply.set(true);
        var response = create(repo, preview(repo, List.of()), true);
        assertThat(response.getBody()).containsEntry("source", "LOCAL_RULES");
        assertThat((Map) response.getBody().get("model")).containsEntry("state", "INVALID_OUTPUT");
        assertThat(modelCalls).hasValue(1);
    }
    @Test void issuesAlreadyAssignedToAutomationRemainReadable() {
        String repo = register(); assignedBot.set(true);
        Map preview = preview(repo, List.of());
        assertThat(((List<?>) ((Map) preview.get("issue")).get("assignees")).getFirst()).isEqualTo("copilot-swe-agent[bot]");
    }
    String register() {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Map) response.getBody().get("registration")).get("id").toString();
    }
    Map preview(String repo, List<String> paths) {
        var response = http.postForEntity(root(repo) + "/previews", new HttpEntity<>(Map.of("number", 2, "codePaths", paths), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody();
    }
    ResponseEntity<Map> create(String repo, Map preview, boolean model) {
        return http.postForEntity(root(repo) + "/reports", new HttpEntity<>(Map.of("previewId", preview.get("id"), "useModel", model), headers()), Map.class);
    }
    static String root(String repo) { return "/api/development/repositories/" + repo + "/issue"; }
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
                    var content = Map.of("summary", "Reporter describes a failing request; reproduction remains unverified.", "category", "BUG", "priority", "P2", "reason", "Reported request failure", "evidenceIds", List.of(invalidCitation.get() ? "invented" : "ISSUE-2"),
                        "questions", List.of("Which version fails?"), "actions", List.of(Map.of("text", "Reproduce the reported request", "evidenceIds", List.of("ISSUE-2"))),
                        "ownerCandidates", List.of(Map.of("login", invalidOwner.get() ? "outsider" : "alice", "reason", "Contributor candidate", "evidenceIds", List.of("OWNER-1"))));
                    data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-issue", "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(content)))), "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                    if (modelError.get()) { status = 500; data = Map.of("error", "private error body"); }
                    if (toolReply.get()) data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-issue", "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call", "type", "function", "function", Map.of("name", "execute", "arguments", "{}")))))));
                } else if (path.endsWith("/issues/2")) {
                    data = Map.of("number", 2, "title", "Failing request", "body", "Request fails in src/main/java/Example.java:3\npassword=\"fixture-private-password\"" + (changed.get() ? "\nChanged content" : ""), "state", "open", "updated_at", "2026-10-14T00:00:00Z", "labels", List.of(Map.of("name", "bug")), "assignees", assignedBot.get() ? List.of(Map.of("login", "copilot-swe-agent[bot]")) : List.of(), "comments", 0);
                } else if (path.equals("/repos/acme/service")) {
                    data = Map.of("full_name", "acme/service", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 1);
                } else if (path.endsWith("/commits/main")) data = Map.of("sha", HEAD);
                else if (path.endsWith("/commits")) data = List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Update Example", "committer", Map.of("date", "2026-10-14T00:00:00Z"))));
                else if (path.endsWith("/contributors")) data = botFirst.get() ? List.of(Map.of("login", "dependabot[bot]", "type", "Bot", "contributions", 100), Map.of("login", "alice", "contributions", 4)) : List.of(Map.of("login", "alice", "contributions", 4));
                else if (path.endsWith("/contents/src/main/java/Example.java")) {
                    String source = (longPrefix.get() ? "//" + "p".repeat(1600) + "\n//" + "p".repeat(1600) : "package acme;\nclass Example {") + "\n public void execute() {}\n String password=\"fixture-private-password\";\n}";
                    data = Map.of("type", "file", "path", "src/main/java/Example.java", "encoding", "base64", "size", source.length(), "content", Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)));
                } else if (path.endsWith("/actions/runs")) data = Map.of("workflow_runs", List.of());
                else if (path.endsWith("/issues") || path.endsWith("/pulls")) data = List.of();
                else { status = 404; data = Map.of(); }
                byte[] bytes = JSON.writeValueAsBytes(data); exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }); server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @AfterAll static void stop() { server.stop(0); serverWorkers.shutdownNow(); }
}
