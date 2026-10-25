package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.development.IssueModels.Issue;
import io.github.mochiuaena.triage.development.IssueModels.ModelResult;
import io.github.mochiuaena.triage.development.IssueModels.Action;
import io.github.mochiuaena.triage.development.IssueModels.Proposal;
import io.github.mochiuaena.triage.development.IssueReportStore;
import io.github.mochiuaena.triage.development.PrModels.Context;
import io.github.mochiuaena.triage.development.PrModels.CiSnapshot;
import io.github.mochiuaena.triage.development.PrModels.FileChange;
import io.github.mochiuaena.triage.development.PrModels.Finding;
import io.github.mochiuaena.triage.development.PrReportStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:draft-execution;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key", "triage.github.write-token=fixture-write-key",
    "triage.github.write-timeout-seconds=1", "triage.github.refresh-seconds=0"
})
class DraftExecutionApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final AtomicBoolean pushDenied = new AtomicBoolean();
    static final AtomicBoolean issueDrifted = new AtomicBoolean();
    static final AtomicBoolean issueClosed = new AtomicBoolean();
    static final AtomicBoolean pullDrifted = new AtomicBoolean();
    static final AtomicBoolean hangWrite = new AtomicBoolean();
    static final AtomicBoolean dropHungWrite = new AtomicBoolean();
    static final AtomicBoolean githubWriteDenied = new AtomicBoolean();
    static final AtomicBoolean labelMismatch = new AtomicBoolean();
    static final List<String> currentLabels = new CopyOnWriteArrayList<>(List.of("bug", "help"));
    static final List<Map<String, Object>> issueComments = new CopyOnWriteArrayList<>();
    static final AtomicInteger commentIds = new AtomicInteger(500);
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssueReportStore issueReports;
    @Autowired PrReportStore prReports;
    String repo;
    UUID issueReportId;
    UUID prReportId;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stop() { server.stop(0); }

    @BeforeEach void reset() {
        calls.clear();
        for (AtomicBoolean flag : List.of(pushDenied, issueDrifted, issueClosed, pullDrifted, hangWrite, dropHungWrite, githubWriteDenied, labelMismatch))
            flag.set(false);
        currentLabels.clear(); currentLabels.addAll(List.of("bug", "help"));
        issueComments.clear();
        jdbc.update("DELETE FROM draft_executions");
        jdbc.update("DELETE FROM operation_drafts");
        jdbc.update("DELETE FROM issue_reports");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM github_repositories");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
        seedLocalReports();
    }

    void seedLocalReports() {
        UUID repositoryId = UUID.fromString(repo);
        Instant time = Instant.parse("2026-10-13T06:00:00Z");
        Issue issue = new Issue(7, "Slow request", "https://github.com/acme/service/issues/7", "open", time, List.of("bug"), List.of(), 0, false);
        Proposal proposal = new Proposal("已整理描述。", "BUG", "P2", "标签依据", List.of("ISSUE-1"), List.of(),
            List.of(new Action("复现问题", List.of("ISSUE-1"))), List.of());
        issueReportId = UUID.randomUUID();
        issueReports.insert(new io.github.mochiuaena.triage.development.IssueModels.Report(issueReportId, repositoryId, "acme/service", issue, "main", HEAD, time, time, "LOCAL_RULES",
            proposal, new ModelResult("SKIPPED", "", null, 0, null, null, 0, "未选择模型。"), List.of(), List.of(), List.of()));
        Context pull = new Context(8, "Fix", "https://github.com/acme/service/pull/8", "open", false, HEAD, HEAD, 1, 2, 1, List.of(2L), false);
        prReportId = UUID.randomUUID();
        prReports.insert(new io.github.mochiuaena.triage.development.PrModels.Report(prReportId, repositoryId, "acme/service", pull, HEAD, time, "GITHUB_RULES",
            List.of(new FileChange("src/A.java", null, "modified", 2, 1, "COMPLETE")),
            List.of(new Finding("PR-CHANGE-1", "SECURITY", "权限变更", "src/A.java", 11, "RIGHT", "permitAll();", "需核对", "核对路径", "https://github.com/acme/service/blob/" + HEAD + "/src/A.java#L11")),
            new CiSnapshot("OBSERVED_SUCCESS", true, true, false, List.of(), List.of()), List.of(), List.of(), "本机差异规则检查。"));
    }

    private ResponseEntity<Map> createDraft(UUID basisId) {
        return http.exchange("/api/development/repositories/" + repo + "/drafts", HttpMethod.POST,
            new HttpEntity<>(Map.of("kind", "COMMENT", "basisReportId", basisId), headers()), Map.class);
    }

    private ResponseEntity<Map> preview(String draftId) {
        return http.postForEntity("/api/development/drafts/" + draftId + "/execution-previews", new HttpEntity<>(Map.of(), headers()), Map.class);
    }

    private ResponseEntity<Map> execute(String draftId, String previewId, Boolean confirmed) {
        return http.exchange("/api/development/drafts/" + draftId + "/executions", HttpMethod.POST,
            new HttpEntity<>(Map.of("previewId", previewId, "confirmed", confirmed), headers()), Map.class);
    }

    private long writeCalls(String suffix) {
        return calls.stream().filter(call -> (call.startsWith("POST ") || call.startsWith("PUT ")) && call.contains(suffix)).count();
    }

    @Test void confirmedExecutionPublishesOnceAndAuditsWithoutCredentials() {
        assertThat(http.getForEntity("/api/development/drafts/write-option", Map.class).getBody())
            .containsEntry("enabled", true);
        String draftId = createDraft(issueReportId).getBody().get("id").toString();
        String body = http.getForEntity("/api/development/drafts/" + draftId, Map.class).getBody().get("body").toString();
        var previewed = preview(draftId);
        assertThat(previewed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map previewBody = previewed.getBody();
        assertThat(previewBody.get("draftId")).isEqualTo(draftId);
        assertThat(((Map<?, ?>) previewBody.get("target")).get("number")).isEqualTo(7);
        assertThat(previewBody.get("body").toString()).isEqualTo(body);
        assertThat(previewBody.get("permitted")).isEqualTo(true);
        assertThat(previewBody.get("expiresAt")).isNotNull();
        String previewId = previewBody.get("id").toString();

        assertThat(execute(draftId, previewId, false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(writeCalls("/comments")).isZero();
        assertThat(execute(draftId, UUID.randomUUID().toString(), true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var executed = execute(draftId, previewId, true);
        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map record = executed.getBody();
        assertThat(record).containsEntry("state", "SUCCESS");
        assertThat(record.get("resultUrl").toString()).isEqualTo("https://github.com/acme/service/issues/7#issuecomment-501");
        assertThat(record.get("body").toString()).isEqualTo(body);
        assertThat(record.get("confirmedAt")).isNotNull();
        assertThat(record.get("executedAt")).isNotNull();
        assertThat(record.toString()).doesNotContain("fixture-write-key", "fixture-github-key");
        assertThat(writeCalls("/issues/7/comments")).isEqualTo(1);
        assertThat(calls.stream().anyMatch(call -> call.equals("POST /repos/acme/service/issues/7/comments fixture-write-key"))).isTrue();
        assertThat(http.getForEntity("/api/development/drafts/" + draftId, Map.class).getBody()).containsEntry("state", "EXECUTED");
        assertThat(execute(draftId, previewId, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/api/development/drafts/" + draftId, HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "body", "不能再改"), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/development/drafts/" + draftId + "/discard", new HttpEntity<>(Map.of(), headers()), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        var audits = http.getForEntity("/api/development/repositories/" + repo + "/draft-executions", List.class).getBody();
        assertThat((List<?>) audits).hasSize(1);
        assertThat(((Map<?, ?>) audits.getFirst()).get("state")).isEqualTo("SUCCESS");
        String auditId = ((Map<?, ?>) audits.getFirst()).get("id").toString();
        assertThat(http.getForEntity("/api/development/draft-executions/" + auditId, Map.class).getBody()).containsEntry("id", auditId);

        String pullDraft = createDraft(prReportId).getBody().get("id").toString();
        String pullPreview = preview(pullDraft).getBody().get("id").toString();
        var pullExecuted = execute(pullDraft, pullPreview, true);
        assertThat(pullExecuted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pullExecuted.getBody()).containsEntry("state", "SUCCESS");
        assertThat(writeCalls("/issues/8/comments")).isEqualTo(1);
    }

    @Test void driftedOrEditedTargetsAreRejectedBeforeAnyWrite() {
        String draftId = createDraft(issueReportId).getBody().get("id").toString();
        issueDrifted.set(true);
        assertThat(preview(draftId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        issueDrifted.set(false);
        issueClosed.set(true);
        assertThat(preview(draftId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        issueClosed.set(false);
        String previewId = preview(draftId).getBody().get("id").toString();
        issueDrifted.set(true);
        var drifted = execute(draftId, previewId, true);
        assertThat(drifted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(drifted.getBody().toString()).contains("目标已变化");
        assertThat(writeCalls("/issues/7/comments")).isZero();

        issueDrifted.set(false);
        String second = createDraft(issueReportId).getBody().get("id").toString();
        String secondPreview = preview(second).getBody().get("id").toString();
        assertThat(http.exchange("/api/development/drafts/" + second, HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "body", "执行前修改了正文。"), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var edited = execute(second, secondPreview, true);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(writeCalls("/issues/7/comments")).isZero();

        String pullDraft = createDraft(prReportId).getBody().get("id").toString();
        String pullPreview = preview(pullDraft).getBody().get("id").toString();
        pullDrifted.set(true);
        assertThat(execute(pullDraft, pullPreview, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(writeCalls("/issues/8/comments")).isZero();
    }

    @Test void labelExecutionComputesResultingSetAndAuditsIt() {
        var label = http.exchange("/api/development/repositories/" + repo + "/drafts", HttpMethod.POST,
            new HttpEntity<>(Map.of("kind", "LABEL", "basisReportId", issueReportId), headers()), Map.class);
        String draftId = label.getBody().get("id").toString();
        Map previewBody = preview(draftId).getBody();
        assertThat((List<?>) previewBody.get("currentLabels")).isEqualTo(List.of("bug", "help"));
        assertThat((List<?>) previewBody.get("resultingLabels")).isEqualTo(List.of("bug", "help", "BUG"));
        String previewId = previewBody.get("id").toString();
        var executed = execute(draftId, previewId, true);
        assertThat(executed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(executed.getBody()).containsEntry("state", "SUCCESS");
        assertThat((List<?>) executed.getBody().get("resultingLabels")).isEqualTo(List.of("bug", "help", "BUG"));
        assertThat(calls.stream().anyMatch(call -> call.startsWith("PUT /repos/acme/service/issues/7/labels"))).isTrue();
        assertThat(http.getForEntity("/api/development/drafts/" + draftId, Map.class).getBody()).containsEntry("state", "EXECUTED");

        var removal = http.exchange("/api/development/repositories/" + repo + "/drafts", HttpMethod.POST,
            new HttpEntity<>(Map.of("kind", "LABEL", "basisReportId", issueReportId), headers()), Map.class);
        String removalId = removal.getBody().get("id").toString();
        assertThat(http.exchange("/api/development/drafts/" + removalId, HttpMethod.PUT, new HttpEntity<>(
            Map.of("revision", 1, "addLabels", List.of(), "removeLabels", List.of("help")), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        labelMismatch.set(true);
        String mismatchPreview = preview(removalId).getBody().get("id").toString();
        var mismatched = execute(removalId, mismatchPreview, true);
        assertThat(mismatched.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(mismatched.getBody()).containsEntry("state", "UNCERTAIN");
        assertThat(mismatched.getBody().toString()).contains("不一致");
        assertThat(http.getForEntity("/api/development/drafts/" + removalId, Map.class).getBody()).containsEntry("state", "DRAFT");
    }

    @Test void permissionProblemsStopBeforeAnyWriteAndFailuresKeepTheDraft() {
        String draftId = createDraft(issueReportId).getBody().get("id").toString();
        pushDenied.set(true);
        var denied = preview(draftId);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().toString()).contains("推送权限");
        assertThat(writeCalls("/issues/7")).isZero();
        pushDenied.set(false);
        String previewId = preview(draftId).getBody().get("id").toString();
        githubWriteDenied.set(true);
        var failed = execute(draftId, previewId, true);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(failed.getBody()).containsEntry("state", "FAILED");
        assertThat(http.getForEntity("/api/development/drafts/" + draftId, Map.class).getBody()).containsEntry("state", "DRAFT");
        assertThat(writeCalls("/issues/7/comments")).isEqualTo(1);
        var audits = http.getForEntity("/api/development/repositories/" + repo + "/draft-executions", List.class).getBody();
        assertThat(((Map<?, ?>) ((List<?>) audits).getFirst()).get("state")).isEqualTo("FAILED");
    }

    @Test void timeoutsVerifyRemoteStateInsteadOfRetrying() {
        String draftId = createDraft(issueReportId).getBody().get("id").toString();
        hangWrite.set(true);
        String previewId = preview(draftId).getBody().get("id").toString();
        var recovered = execute(draftId, previewId, true);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recovered.getBody()).containsEntry("state", "SUCCESS");
        assertThat(recovered.getBody().toString()).contains("核对");
        assertThat(writeCalls("/issues/7/comments")).isEqualTo(1);

        hangWrite.set(true);
        dropHungWrite.set(true);
        String second = createDraft(issueReportId).getBody().get("id").toString();
        assertThat(http.exchange("/api/development/drafts/" + second, HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "body", "超时后远端没有落地的评论。"), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondPreview = preview(second).getBody().get("id").toString();
        var uncertain = execute(second, secondPreview, true);
        assertThat(uncertain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uncertain.getBody()).containsEntry("state", "UNCERTAIN");
        assertThat(http.getForEntity("/api/development/drafts/" + second, Map.class).getBody()).containsEntry("state", "DRAFT");
        assertThat(writeCalls("/issues/7/comments")).isEqualTo(2);
        hangWrite.set(false);
        dropHungWrite.set(false);
        String retryPreview = preview(second).getBody().get("id").toString();
        assertThat(execute(second, retryPreview, true).getBody()).containsEntry("state", "SUCCESS");
        assertThat(writeCalls("/issues/7/comments")).isEqualTo(3);
    }

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(6, Thread.ofPlatform().daemon(true).name("exec-fixture-", 0).factory()));
            server.createContext("/", exchange -> {
                try { handle(exchange); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    static void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String token = exchange.getRequestHeaders().getFirst("Authorization");
        calls.add(method + " " + path + (token == null ? "" : " " + token.replace("Bearer ", "")));
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        if (githubWriteDenied.get() && (method.equals("POST") || method.equals("PUT"))) {
            byte[] denial = JSON.writeValueAsBytes(Map.of("message", "denied"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, denial.length);
            try (var out = exchange.getResponseBody()) { out.write(denial); }
            return;
        }
        if (hangWrite.get() && (path.endsWith("/comments") || path.endsWith("/labels")) && (method.equals("POST") || method.equals("PUT"))) {
            if (!dropHungWrite.get()) perform(method, path, requestBytes);
            Thread.sleep(2500);
        } else perform(method, path, requestBytes);
        Object data = response(method, path);
        byte[] bytes = JSON.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status(method, path), bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    /** Mutates fixture state for writes; read requests are stateless. */
    private static void perform(String method, String path, byte[] body) throws Exception {
        if (method.equals("POST") && path.matches(".*/issues/\\d+/comments")) {
            long id = commentIds.incrementAndGet();
            var payload = JSON.readValue(body, Map.class);
            issueComments.add(Map.of("id", id, "body", payload.get("body"),
                "html_url", "https://github.com/acme/service/issues/" + path.replaceAll(".*issues/(\\d+)/comments", "$1") + "#issuecomment-" + id,
                "created_at", "2026-10-25T00:00:00Z"));
        } else if (method.equals("PUT") && path.endsWith("/labels")) {
            var payload = JSON.readValue(body, Map.class);
            if (!labelMismatch.get()) { currentLabels.clear(); currentLabels.addAll((List<String>) payload.get("labels")); }
        }
    }

    private static int status(String method, String path) {
        if (method.equals("POST") && path.matches(".*/issues/\\d+/comments")) return 201;
        return 200;
    }

    private static Object response(String method, String path) {
        if (path.equals("/repos/acme/service"))
            return Map.of("full_name", "acme/service", "description", "Example", "default_branch", "main", "private", false,
                "archived", false, "open_issues_count", 2, "permissions", Map.of("push", !pushDenied.get()));
        if (path.matches(".*/issues/\\d+$")) {
            long number = Long.parseLong(path.replaceAll(".*/issues/(\\d+)$", "$1"));
            Map<String, Object> issue = new HashMap<>(Map.of("number", number, "title", number == 7 ? "Slow request" : "PR",
                "state", issueClosed.get() ? "closed" : "open",
                "updated_at", issueDrifted.get() ? "2026-10-14T00:00:00Z" : "2026-10-13T06:00:00Z",
                "labels", currentLabels.stream().map(label -> Map.of("name", label)).toList(), "assignees", List.of()));
            if (number == 8) issue.put("pull_request", Map.of());
            return issue;
        }
        if (path.matches(".*/pulls/\\d+$"))
            return Map.of("number", 8, "title", "Fix", "state", "open", "updated_at", "2026-10-13T06:00:00Z",
                "head", Map.of("sha", pullDrifted.get() ? "b".repeat(40) : HEAD));
        if (path.matches(".*/issues/\\d+/labels$")) {
            // Only the write response may disagree with the applied payload; reads always return the current state.
            List<String> labels = labelMismatch.get() && method.equals("PUT") ? List.of("other") : List.copyOf(currentLabels);
            return labels.stream().map(label -> Map.of("name", label)).toList();
        }
        if (path.matches(".*/issues/\\d+/comments$"))
            return method.equals("POST") ? issueComments.getLast() : List.copyOf(issueComments);
        return Map.of();
    }
}
