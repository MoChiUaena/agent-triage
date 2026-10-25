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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:repo-health;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key", "triage.github.refresh-seconds=0"
})
class RepositoryHealthApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final AtomicBoolean actionsDenied = new AtomicBoolean();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("repo-health-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired CiReportStore ciReports;
    @Autowired PrReportStore prReports;
    @Autowired IssueReportStore issueReports;
    String repo;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stop() { server.stop(0); serverWorkers.shutdownNow(); }

    @BeforeEach void reset() {
        actionsDenied.set(false);
        jdbc.update("DELETE FROM repository_health_reports");
        jdbc.update("DELETE FROM issue_reports");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM ci_reports");
        jdbc.update("DELETE FROM github_repositories");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
        seedLocalReports();
    }

    /** One saved analysis record of each kind, so the summary has local history to aggregate. */
    void seedLocalReports() {
        UUID repositoryId = UUID.fromString(repo);
        Instant time = Instant.parse("2026-10-24T00:00:00Z");
        ciReports.insert(new Report(UUID.randomUUID(), repositoryId, "acme/service", 9901, 1, HEAD, "Verify",
            new Job(777, 9901, 1, HEAD, "Build", "completed", "failure",
                List.of(new Step(1, "Build", "failure", time, time.plusSeconds(9))), "https://github.com/acme/service/actions/runs/9901/job/777"),
            "GITHUB", time, "来自所选 GitHub 作业日志。",
            new Analysis("COMPILATION_FAILURE", "OBSERVED", "编译阶段失败", "错误片段包含编译错误。", List.of("核对文件与行号。"),
                List.of(new Evidence("CI-LOG-1", 10, 11, "[ERROR] cannot find symbol")), List.of(), "只扫描失败步骤。", false)));
        Context pull = new Context(8, "Fix", "https://github.com/acme/service/pull/8", "open", false, HEAD, HEAD, 1, 2, 1, List.of(2L), false);
        prReports.insert(new io.github.mochiuaena.triage.development.PrModels.Report(UUID.randomUUID(), repositoryId, "acme/service", pull, HEAD, time, "GITHUB_RULES",
            List.of(new FileChange("src/A.java", null, "modified", 2, 1, "COMPLETE")),
            List.of(new Finding("PR-CHANGE-1", "SECURITY", "权限变更", "src/A.java", 11, "RIGHT", "permitAll();", "需核对", "核对路径", "https://github.com/acme/service/blob/" + HEAD + "/src/A.java#L11")),
            new CiSnapshot("OBSERVED_SUCCESS", true, true, false, List.of(), List.of()), List.of(), List.of(), "本机差异规则检查。"));
        Issue issue = new Issue(7, "Slow request", "https://github.com/acme/service/issues/7", "open", time, List.of("bug"), List.of(), 0, false);
        Proposal proposal = new Proposal("已整理描述。", "BUG", "P2", "标签依据", List.of("ISSUE-1"), List.of(),
            List.of(new Action("复现问题", List.of("ISSUE-1"))), List.of());
        issueReports.insert(new io.github.mochiuaena.triage.development.IssueModels.Report(UUID.randomUUID(), repositoryId, "acme/service", issue, "main", HEAD, time, time, "LOCAL_RULES",
            proposal, new ModelResult("SKIPPED", "", null, 0, null, null, 0, "未选择模型。"), List.of(), List.of(), List.of()));
    }

    private <T> ResponseEntity<T> generate(Class<T> type) {
        return http.exchange("/api/development/repositories/" + repo + "/health-summaries", HttpMethod.POST,
            new HttpEntity<>(null, headers()), type);
    }

    @SuppressWarnings("unchecked")
    private Map section(Map report, String kind) {
        return (Map) ((List<?>) report.get("sections")).stream()
            .filter(item -> ((Map<?, ?>) item).get("kind").equals(kind)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map> localRecord(Map report, String kind) {
        return (List<Map>) ((List<?>) report.get("localRecords")).stream()
            .filter(item -> ((Map<?, ?>) item).get("kind").equals(kind)).toList();
    }

    @Test void generatesRuleSummaryWithSourcesScopeAndFailureItems() {
        var created = generate(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map report = created.getBody();
        assertThat(report.get("repositoryId")).isEqualTo(repo);
        assertThat(report).containsEntry("repository", "acme/service");
        assertThat(report.get("createdAt")).isNotNull();
        assertThat((List<?>) report.get("sections")).hasSize(3);
        Map issues = section(report, "ISSUES");
        assertThat(issues).containsEntry("available", true).containsEntry("listed", 1).containsEntry("truncated", false);
        Map workflows = section(report, "WORKFLOWS");
        assertThat((int) workflows.get("failing")).isEqualTo(1);
        assertThat(workflows.get("failingRuns").toString()).contains("Verify").contains("failure");
        assertThat(report.get("notes").toString()).contains("快照").contains("抽样").contains("不代表仓库全部状态");
        assertThat((int) localRecord(report, "CI").size()).isEqualTo(1);
        assertThat(localRecord(report, "CI").getFirst().get("latestTitle").toString()).contains("Verify");
        assertThat((int) localRecord(report, "PR").size()).isEqualTo(1);
        assertThat((int) localRecord(report, "ISSUE").size()).isEqualTo(1);
        assertThat(report.get("notes").toString()).doesNotContain("过期");

        var list = http.getForEntity("/api/development/repositories/" + repo + "/health-summaries", List.class).getBody();
        assertThat((List<?>) list).hasSize(1);
        String id = report.get("id").toString();
        assertThat(http.getForEntity("/api/development/health-summaries/" + id, Map.class).getBody()).containsEntry("id", id);
        assertThat(http.getForEntity("/api/development/health-summaries/" + UUID.randomUUID(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void failedSnapshotReadPromptsRefreshButStillGenerates() {
        jdbc.update("UPDATE github_repositories SET last_error=? WHERE id=?", "GitHub 读取失败（fixture）", repo);
        var created = generate(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Boolean) created.getBody().get("stale")).isTrue();
        assertThat(created.getBody().get("notes").toString()).contains("过期或上次读取失败").contains("更新 GitHub 状态");
    }

    @Test void unavailableSectionsBecomeExplicitGaps() {
        actionsDenied.set(true);
        var refreshed = http.postForEntity("/api/development/repositories/" + repo + "/refresh", new HttpEntity<>(Map.of(), headers()), Map.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        var created = generate(Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map workflows = section(created.getBody(), "WORKFLOWS");
        assertThat(workflows).containsEntry("available", false);
        assertThat(created.getBody().get("notes").toString()).contains("WORKFLOWS").contains("未读取");
    }

    @Test void historyIsListedAndRemovedWithTheRegistration() {
        generate(Map.class);
        generate(Map.class);
        var list = http.getForEntity("/api/development/repositories/" + repo + "/health-summaries", List.class).getBody();
        assertThat((List<?>) list).hasSize(2);
        assertThat(((Map<?, ?>) ((List<?>) list).getFirst()).get("createdAt"))
            .isNotNull();
        http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repository_health_reports", Long.class)).isEqualTo(0);
        assertThat(http.getForEntity("/api/development/repositories/" + UUID.randomUUID() + "/health-summaries", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.equals("/repos/acme/service")) {
                    data = Map.of("full_name", "acme/service", "description", "Example", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 2);
                } else if (path.equals("/repos/acme/service/issues")) {
                    data = List.of(Map.of("number", 7, "title", "Slow request", "updated_at", "2026-10-13T00:00:00Z", "labels", List.of()),
                        Map.of("number", 8, "title", "PR", "pull_request", Map.of()));
                } else if (path.equals("/repos/acme/service/pulls")) {
                    data = List.of(Map.of("number", 8, "title", "Fix", "draft", true, "updated_at", "2026-10-13T00:00:00Z",
                        "head", Map.of("sha", HEAD)));
                } else if (path.equals("/repos/acme/service/actions/runs")) {
                    if (actionsDenied.get()) { status = 403; data = Map.of("message", "denied"); }
                    else data = Map.of("workflow_runs", List.of(Map.of("id", 9, "name", "Verify", "status", "completed", "conclusion", "failure",
                        "head_branch", "main", "head_sha", HEAD, "updated_at", "2026-10-13T00:00:00Z", "html_url", "https://github.com/acme/service/actions/runs/9")));
                } else if (path.endsWith("/commits/main")) {
                    data = Map.of("sha", HEAD);
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
