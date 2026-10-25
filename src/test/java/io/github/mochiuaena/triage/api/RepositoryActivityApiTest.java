package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:repo-activity;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key", "triage.github.refresh-seconds=0"
})
class RepositoryActivityApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final AtomicBoolean actionsDenied = new AtomicBoolean();
    static final AtomicBoolean commitsDenied = new AtomicBoolean();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("repo-activity-fixture-", 0).factory());
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
        commitsDenied.set(false);
        jdbc.update("DELETE FROM repository_activity_reports");
        jdbc.update("DELETE FROM issue_reports");
        jdbc.update("DELETE FROM pr_reports");
        jdbc.update("DELETE FROM ci_reports");
        jdbc.update("DELETE FROM github_repositories");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
        seedLocalReports();
    }

    /** One saved analysis record of each kind inside the fixture range, so local history is aggregated by date. */
    void seedLocalReports() {
        UUID repositoryId = UUID.fromString(repo);
        Instant time = Instant.parse("2026-10-13T06:00:00Z");
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

    private ResponseEntity<Map> generate(String from, String to) {
        return http.exchange("/api/development/repositories/" + repo + "/activity-reports", HttpMethod.POST,
            new HttpEntity<>(Map.of("fromDate", from, "toDate", to), headers()), Map.class);
    }

    private static List<String> itemKinds(Map report) {
        var kinds = new ArrayList<String>();
        for (Object day : (List<?>) report.get("days"))
            for (Object item : (List<?>) ((Map<?, ?>) day).get("items")) kinds.add(((Map<?, ?>) item).get("kind").toString());
        return kinds;
    }

    private static String notesText(Map report) { return report.get("notes").toString(); }

    private static String gapsText(Map report) { return report.get("gaps").toString(); }

    @Test void generatesTraceableReportWithMarkdownExport() {
        var created = generate("2026-10-13", "2026-10-13");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map report = created.getBody();
        assertThat(report.get("repositoryId")).isEqualTo(repo);
        assertThat(report).containsEntry("repository", "acme/service");
        assertThat(report).containsEntry("fromDate", "2026-10-13").containsEntry("toDate", "2026-10-13");
        assertThat(report.get("snapshotReadAt")).isNotNull();
        assertThat((List<?>) report.get("days")).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) report.get("days")).getFirst()).get("date")).isEqualTo("2026-10-13");
        var kinds = itemKinds(report);
        assertThat(kinds).contains("COMMIT", "ISSUE", "PULL_REQUEST", "WORKFLOW", "CI_REPORT", "PR_REPORT", "ISSUE_REPORT");
        for (Object day : (List<?>) report.get("days"))
            for (Object value : (List<?>) ((Map<?, ?>) day).get("items")) {
                Map<?, ?> item = (Map<?, ?>) value;
                if (item.get("reportId") == null)
                    assertThat(item.get("url").toString()).startsWith("https://github.com/acme/service/");
                else {
                    assertThat(item.get("reportType")).isIn("ci", "pr", "issue");
                    assertThat(item.get("url").toString()).startsWith("https://github.com/acme/service/");
                }
            }
        var commit = itemByKind(report, "COMMIT");
        assertThat(commit.get("title").toString()).isEqualTo("Fix login timeout");
        assertThat(commit.get("detail").toString()).contains("dev").contains(HEAD.substring(0, 7));
        var actionKinds = new ArrayList<String>();
        for (Object value : (List<?>) report.get("actionItems")) actionKinds.add(((Map<?, ?>) value).get("kind").toString());
        assertThat(actionKinds).contains("FAILING_WORKFLOW", "OPEN_ISSUE", "OPEN_PULL_REQUEST");
        var local = (List<?>) report.get("localRecords");
        assertThat(local.stream().anyMatch(value -> ((Map<?, ?>) value).get("kind").equals("CI")
            && ((Map<?, ?>) value).get("inRange").equals(1) && ((Map<?, ?>) value).get("saved").equals(1))).isTrue();
        assertThat(gapsText(report)).isEqualTo("[]");
        assertThat(notesText(report)).contains("抽样").contains("完整列表以 GitHub 为准").contains("回写知识库").contains("不自动发送邮件");

        String id = report.get("id").toString();
        assertThat(http.getForEntity("/api/development/activity-reports/" + id, Map.class).getBody()).containsEntry("id", id);
        var markdown = http.getForEntity("/api/development/activity-reports/" + id + "/markdown", String.class);
        assertThat(markdown.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(markdown.getHeaders().getContentType().toString()).contains("text/markdown");
        assertThat(markdown.getHeaders().getFirst("Content-Disposition")).contains(id);
        String body = markdown.getBody();
        assertThat(body).contains("# 研发活动报告 · acme/service");
        assertThat(body).contains("### 2026-10-13");
        assertThat(body).contains("[#7 Slow request](https://github.com/acme/service/issues/7)");
        assertThat(body).contains("[Fix login timeout](https://github.com/acme/service/commit/" + HEAD + ")");
        assertThat(body).contains("抽样");
        assertThat(body).contains("不自动发送邮件");
        assertThat(body).doesNotContain("全量统计");
        assertThat(http.getForEntity("/api/development/activity-reports/" + UUID.randomUUID(), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static Map<?, ?> itemByKind(Map report, String kind) {
        for (Object day : (List<?>) report.get("days"))
            for (Object value : (List<?>) ((Map<?, ?>) day).get("items"))
                if (((Map<?, ?>) value).get("kind").equals(kind)) return (Map<?, ?>) value;
        throw new AssertionError("Missing item kind " + kind);
    }

    @Test void emptyRangeIsDistinguishedFromReadFailures() {
        var created = generate("2026-10-01", "2026-10-02");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map report = created.getBody();
        assertThat((List<?>) report.get("days")).isEmpty();
        assertThat(notesText(report)).contains("没有匹配的活动记录").contains("不代表该时段没有其他活动");
        assertThat(gapsText(report)).isEqualTo("[]");
    }

    @Test void readFailuresAndLegacySnapshotsBecomeExplicitGaps() throws Exception {
        actionsDenied.set(true);
        commitsDenied.set(true);
        assertThat(http.postForEntity("/api/development/repositories/" + repo + "/refresh", new HttpEntity<>(Map.of(), headers()), Map.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        var denied = generate("2026-10-13", "2026-10-13");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(gapsText(denied.getBody())).contains("提交区段未读取").contains("工作流区段未读取").contains("不代表该时段没有相关活动");
        assertThat(itemKinds(denied.getBody())).doesNotContain("COMMIT", "WORKFLOW");

        // A snapshot written before the commits section existed decodes without it; it must stay a gap, not an error.
        String payload = jdbc.queryForObject("SELECT snapshot FROM github_repositories WHERE id=?", String.class, repo);
        JsonNode node = JSON.readTree(payload);
        ((ObjectNode) node).remove("commits");
        jdbc.update("UPDATE github_repositories SET snapshot=? WHERE id=?", JSON.writeValueAsString(node), repo);
        var legacy = generate("2026-10-13", "2026-10-13");
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(gapsText(legacy.getBody())).contains("提交区段").contains("旧版本快照");
    }

    @Test void staleSnapshotKeepsReportAndValidatesRepository() {
        jdbc.update("UPDATE github_repositories SET last_error=? WHERE id=?", "GitHub 读取失败（fixture）", repo);
        var created = generate("2026-10-13", "2026-10-13");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Boolean) created.getBody().get("stale")).isTrue();
        assertThat(notesText(created.getBody())).contains("过期或上次读取失败").contains("更新 GitHub 状态");
        assertThat(http.exchange("/api/development/repositories/" + UUID.randomUUID() + "/activity-reports", HttpMethod.POST,
            new HttpEntity<>(Map.of("fromDate", "2026-10-13", "toDate", "2026-10-13"), headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void historyIsListedReopenedAndRemovedWithTheRegistration() {
        assertThat(generate("2026-10-13", "2026-10-13").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(generate("2026-10-07", "2026-10-13").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var list = http.getForEntity("/api/development/repositories/" + repo + "/activity-reports", List.class).getBody();
        assertThat((List<?>) list).hasSize(2);
        String id = ((Map<?, ?>) ((List<?>) list).getFirst()).get("id").toString();
        assertThat(http.getForEntity("/api/development/activity-reports/" + id, Map.class).getBody()).containsEntry("id", id);
        http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repository_activity_reports", Long.class)).isEqualTo(0);
        assertThat(http.getForEntity("/api/development/repositories/" + UUID.randomUUID() + "/activity-reports", String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void rangeBoundariesAreValidated() {
        assertThat(generate("2026-10-14", "2026-10-13").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generate(LocalDate.now().minusDays(31).toString(), LocalDate.now().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generate(LocalDate.now().minusDays(30).toString(), LocalDate.now().toString()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(generate(LocalDate.now().minusDays(3).toString(), LocalDate.now().plusDays(1).toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/development/repositories/" + repo + "/activity-reports", HttpMethod.POST,
            new HttpEntity<>(Map.of("fromDate", "2026-10-13"), headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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
                    data = List.of(Map.of("number", 7, "title", "Slow request", "updated_at", "2026-10-13T12:00:00Z", "labels", List.of("bug")),
                        Map.of("number", 8, "title", "PR", "pull_request", Map.of()));
                } else if (path.equals("/repos/acme/service/pulls")) {
                    data = List.of(Map.of("number", 8, "title", "Fix", "draft", true, "updated_at", "2026-10-13T12:00:00Z",
                        "head", Map.of("sha", HEAD)));
                } else if (path.equals("/repos/acme/service/actions/runs")) {
                    if (actionsDenied.get()) { status = 403; data = Map.of("message", "denied"); }
                    else data = Map.of("workflow_runs", List.of(Map.of("id", 9, "name", "Verify", "status", "completed", "conclusion", "failure",
                            "head_branch", "main", "head_sha", HEAD, "updated_at", "2026-10-13T12:00:00Z", "html_url", "https://github.com/acme/service/actions/runs/9"),
                        Map.of("id", 10, "name", "Build", "status", "completed", "conclusion", "success",
                            "head_branch", "main", "head_sha", HEAD, "updated_at", "2026-10-13T12:30:00Z", "html_url", "https://github.com/acme/service/actions/runs/10")));
                } else if (path.equals("/repos/acme/service/commits")) {
                    if (commitsDenied.get()) { status = 403; data = Map.of("message", "denied"); }
                    else data = List.of(
                        Map.of("sha", HEAD, "commit", Map.of("message", "Fix login timeout\n\nReviewed-by: dev",
                            "author", Map.of("name", "dev", "date", "2026-10-13T12:00:00Z"))),
                        Map.of("sha", "b".repeat(40), "commit", Map.of("message", "Old change",
                            "author", Map.of("name", "other", "date", "2026-09-01T12:00:00Z"))));
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
