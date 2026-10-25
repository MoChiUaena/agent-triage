package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.development.CiModels.Analysis;
import io.github.mochiuaena.triage.development.CiModels.Evidence;
import io.github.mochiuaena.triage.development.CiModels.Job;
import io.github.mochiuaena.triage.development.CiModels.Report;
import io.github.mochiuaena.triage.development.CiModels.Step;
import io.github.mochiuaena.triage.development.CiReportStore;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:operation-drafts;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key", "triage.github.refresh-seconds=0"
})
class OperationDraftApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final List<String> githubCalls = new CopyOnWriteArrayList<>();
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CiReportStore ciReports;
    @Autowired PrReportStore prReports;
    @Autowired IssueReportStore issueReports;
    String repo;
    String other;
    UUID issueReportId;
    UUID prReportId;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stop() { server.stop(0); }

    @BeforeEach void reset() {
        githubCalls.clear();
        jdbc.update("DELETE FROM operation_drafts");
        jdbc.update("DELETE FROM issue_reports");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM ci_reports");
        jdbc.update("DELETE FROM github_repositories");
        repo = register("acme/service");
        other = register("acme/other");
        seedLocalReports();
    }

    String register(String repository) {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", repository), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
    }

    /** Basis reports for drafts: one Issue triage report and one PR review report on the main repository. */
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
        ciReports.insert(new Report(UUID.randomUUID(), repositoryId, "acme/service", 9901, 1, HEAD, "Verify",
            new Job(777, 9901, 1, HEAD, "Build", "completed", "failure",
                List.of(new Step(1, "Build", "failure", time, time.plusSeconds(9))), "https://github.com/acme/service/actions/runs/9901/job/777"),
            "GITHUB", time, "来自所选 GitHub 作业日志。",
            new Analysis("COMPILATION_FAILURE", "OBSERVED", "编译阶段失败", "错误片段包含编译错误。", List.of("核对文件与行号。"),
                List.of(new Evidence("CI-LOG-1", 10, 11, "[ERROR] cannot find symbol")), List.of(), "只扫描失败步骤。", false)));
    }

    private ResponseEntity<Map> create(String repository, String kind, UUID basisId) {
        return http.exchange("/api/development/repositories/" + repository + "/drafts", HttpMethod.POST,
            new HttpEntity<>(Map.of("kind", kind, "basisReportId", basisId), headers()), Map.class);
    }

    private ResponseEntity<Map> update(String id, Object body) {
        return http.exchange("/api/development/drafts/" + id, HttpMethod.PUT, new HttpEntity<>(body, headers()), Map.class);
    }

    @Test void createsDraftsFromSavedReportsWithBasisTargetAndDefaults() {
        assertThat(http.exchange("/api/development/repositories/" + repo + "/drafts", HttpMethod.POST,
            new HttpEntity<>(Map.of("kind", "COMMENT", "basisReportId", issueReportId), plainHeaders()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        int callsBefore = githubCalls.size();
        var comment = create(repo, "COMMENT", issueReportId);
        assertThat(comment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map draft = comment.getBody();
        assertThat(draft.get("repositoryId")).isEqualTo(repo);
        assertThat(draft).containsEntry("kind", "COMMENT").containsEntry("state", "DRAFT").containsEntry("revision", 1);
        Map target = (Map<?, ?>) draft.get("target");
        assertThat(target).containsEntry("objectType", "ISSUE").containsEntry("number", 7).containsEntry("title", "Slow request");
        assertThat(target.get("url").toString()).isEqualTo("https://github.com/acme/service/issues/7");
        assertThat(target.get("updatedAt")).isNotNull();
        Map basis = (Map<?, ?>) draft.get("basis");
        assertThat(basis).containsEntry("type", "ISSUE_REPORT");
        assertThat(basis.get("reportId")).isEqualTo(issueReportId.toString());
        assertThat(basis.get("title").toString()).contains("#7");
        assertThat(draft.get("body").toString()).contains("分诊摘要").contains("复现问题").contains("未执行任何 GitHub 操作");
        assertThat((List<?>) draft.get("addLabels")).isEmpty();

        var label = create(repo, "LABEL", issueReportId);
        assertThat(label.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) label.getBody().get("addLabels")).isEqualTo(List.of("BUG"));
        assertThat(label.getBody().get("body").toString()).contains("BUG").contains("P2");

        var pullComment = create(repo, "COMMENT", prReportId);
        assertThat(pullComment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map pullTarget = (Map<?, ?>) pullComment.getBody().get("target");
        assertThat(pullTarget).containsEntry("objectType", "PULL_REQUEST").containsEntry("number", 8);
        assertThat(pullTarget.get("headSha")).isEqualTo(HEAD);
        assertThat(pullComment.getBody().get("body").toString()).contains("审查要点").contains("权限变更");
        assertThat(((Map<?, ?>) pullComment.getBody().get("basis")).get("type").toString()).isEqualTo("PR_REPORT");

        var list = http.getForEntity("/api/development/repositories/" + repo + "/drafts", List.class).getBody();
        assertThat((List<?>) list).hasSize(3);
        String id = draft.get("id").toString();
        assertThat(http.getForEntity("/api/development/drafts/" + id, Map.class).getBody()).containsEntry("id", id);
        assertThat(http.getForEntity("/api/development/drafts/" + UUID.randomUUID(), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(githubCalls.size()).isEqualTo(callsBefore);
        assertThat(githubCalls).allMatch(call -> call.startsWith("GET "), "Draft operations must never call GitHub writes");
    }

    @Test void editsAreRevisionCheckedAndValidated() {
        String id = create(repo, "COMMENT", issueReportId).getBody().get("id").toString();
        assertThat(update(id, Map.of("revision", 0, "body", "过期修订")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var updated = update(id, Map.of("revision", 1, "body", "更新后的评论内容"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("revision", 2);
        assertThat(((Map<?, ?>) updated.getBody().get("basis")).get("reportId")).isEqualTo(issueReportId.toString());
        assertThat(((Map<?, ?>) updated.getBody().get("target")).get("number")).isEqualTo(7);
        assertThat(update(id, Map.of("revision", 1, "body", "再改")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(update(id, Map.of("revision", 2, "body", "   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(id, Map.of("revision", 2, "body", "x".repeat(20001))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(id, Map.of("revision", 2, "body", "内容", "addLabels", List.of("x"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String labelId = create(repo, "LABEL", issueReportId).getBody().get("id").toString();
        assertThat(update(labelId, Map.of("revision", 1, "addLabels", List.of(), "removeLabels", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(labelId, Map.of("revision", 1, "addLabels", List.of("bug", "bug"), "removeLabels", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(labelId, Map.of("revision", 1, "addLabels", List.of("a"), "removeLabels", List.of("a"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(labelId, Map.of("revision", 1, "addLabels", Arrays.asList("l", "l", "l", "l", "l", "l", "l", "l", "l", "l", "l"), "removeLabels", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(update(labelId, Map.of("revision", 1, "addLabels", List.of("x".repeat(65)), "removeLabels", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var labels = update(labelId, Map.of("revision", 1, "body", "标签说明", "addLabels", List.of("BUG", "help wanted"), "removeLabels", List.of("wontfix")));
        assertThat(labels.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) labels.getBody().get("addLabels")).isEqualTo(List.of("BUG", "help wanted"));
        assertThat((List<?>) labels.getBody().get("removeLabels")).isEqualTo(List.of("wontfix"));
        assertThat(labels.getBody().get("body").toString()).contains("标签说明");
    }

    @Test void discardRemovesFromActiveListAndKeepsTheRecord() {
        String id = create(repo, "COMMENT", issueReportId).getBody().get("id").toString();
        var discarded = http.postForEntity("/api/development/drafts/" + id + "/discard", new HttpEntity<>(Map.of(), headers()), Map.class);
        assertThat(discarded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(discarded.getBody()).containsEntry("state", "DISCARDED");
        assertThat((List<?>) http.getForEntity("/api/development/repositories/" + repo + "/drafts", List.class).getBody()).isEmpty();
        assertThat(http.getForEntity("/api/development/drafts/" + id, Map.class).getBody()).containsEntry("state", "DISCARDED");
        assertThat(update(id, Map.of("revision", 1, "body", "不能再改")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/development/drafts/" + id + "/discard", new HttpEntity<>(Map.of(), headers()), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void basisRepositoryAndKindBoundariesAreEnforced() {
        assertThat(create(repo, "CREATE_ISSUE", issueReportId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(repo, "LABEL", prReportId).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(repo, "COMMENT", UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID otherIssueReport = seedIssueReportFor(UUID.fromString(other));
        assertThat(create(repo, "COMMENT", otherIssueReport).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(UUID.randomUUID().toString(), "COMMENT", issueReportId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/development/repositories/" + UUID.randomUUID() + "/drafts", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);

        create(repo, "COMMENT", issueReportId);
        http.exchange("/api/development/repositories/" + other + "?confirmId=" + other, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_drafts", Long.class)).isEqualTo(1);
        http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_drafts", Long.class)).isEqualTo(0);
    }

    UUID seedIssueReportFor(UUID repositoryId) {
        Instant time = Instant.parse("2026-10-13T06:00:00Z");
        Issue issue = new Issue(9, "Other repo issue", "https://github.com/acme/other/issues/9", "open", time, List.of(), List.of(), 0, false);
        Proposal proposal = new Proposal("描述。", "BUG", "P3", "依据", List.of(), List.of(), List.of(), List.of());
        UUID id = UUID.randomUUID();
        issueReports.insert(new io.github.mochiuaena.triage.development.IssueModels.Report(id, repositoryId, "acme/other", issue, "main", HEAD, time, time, "LOCAL_RULES",
            proposal, new ModelResult("SKIPPED", "", null, 0, null, null, 0, "未选择模型。"), List.of(), List.of(), List.of()));
        return id;
    }

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    static HttpHeaders plainHeaders() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("draft-fixture-", 0).factory()));
            server.createContext("/", exchange -> {
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                githubCalls.add(method + " " + path);
                Object data = switch (path) {
                    case "/repos/acme/service" -> Map.of("full_name", "acme/service", "description", "Example", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 2);
                    case "/repos/acme/other" -> Map.of("full_name", "acme/other", "description", "Other", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 0);
                    case "/repos/acme/service/issues" -> List.of(Map.of("number", 7, "title", "Slow request", "updated_at", "2026-10-13T12:00:00Z", "labels", List.of("bug")),
                        Map.of("number", 8, "title", "PR", "pull_request", Map.of()));
                    case "/repos/acme/service/pulls" -> List.of(Map.of("number", 8, "title", "Fix", "draft", true, "updated_at", "2026-10-13T12:00:00Z", "head", Map.of("sha", HEAD)));
                    case "/repos/acme/service/actions/runs" -> Map.of("workflow_runs", List.of(Map.of("id", 9, "name", "Verify", "status", "completed", "conclusion", "failure",
                        "head_branch", "main", "head_sha", HEAD, "updated_at", "2026-10-13T12:00:00Z", "html_url", "https://github.com/acme/service/actions/runs/9")));
                    case "/repos/acme/service/commits" -> List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Fix login timeout",
                        "author", Map.of("name", "dev", "date", "2026-10-13T12:00:00Z"))));
                    case "/repos/acme/other/issues", "/repos/acme/other/pulls", "/repos/acme/other/commits" -> List.of();
                    case "/repos/acme/other/actions/runs" -> Map.of("workflow_runs", List.of());
                    default -> Map.of();
                };
                byte[] bytes = JSON.writeValueAsBytes(data);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
