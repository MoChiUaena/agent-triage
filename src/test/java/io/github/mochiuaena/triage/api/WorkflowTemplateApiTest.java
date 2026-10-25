package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:workflow-templates;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-template-key", "triage.development.workflow-timeout=60s"
})
class WorkflowTemplateApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), OLD = "c".repeat(40);
    static final ExecutorService fixtureWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("template-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    String repo;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM workflow_templates");
        jdbc.update("DELETE FROM development_workflows");
        jdbc.update("DELETE FROM github_repositories");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map) response.getBody().get("registration")).get("id").toString();
    }
    @AfterAll static void stop() { server.stop(0); fixtureWorkers.shutdownNow(); }

    private ResponseEntity<Map> createTemplate(String name, List<Object> tasks) {
        return http.exchange("/api/development/repositories/" + repo + "/workflow-templates", HttpMethod.POST,
            new HttpEntity<>(Map.of("name", name, "tasks", tasks), headers()), Map.class);
    }

    private static List<Object> ids(String... values) {
        var tasks = new ArrayList<Object>();
        for (String value : values) tasks.add(Map.of("id", value));
        return tasks;
    }

    private Map templatePlan(Object templateId, Map input) {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflow-templates/" + templateId + "/plans",
            new HttpEntity<>(input, headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map execute(Map plan) {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflows",
            new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return finished(response.getBody().get("id").toString());
    }

    private Map finished(String id) {
        var result = new AtomicReference<Map>();
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(25)).until(() -> {
            Map value = http.getForObject("/api/development/workflows/" + id, Map.class);
            result.set(value);
            return !Set.of("PLANNED", "RUNNING").contains(value.get("state"));
        });
        return result.get();
    }

    static Map step(Map result, String id) {
        return (Map) ((List<?>) result.get("tasks")).stream().map(value -> (Map) value).filter(value -> value.get("id").equals(id)).findFirst().orElseThrow();
    }

    @Test void catalogIsListedAndTemplatesAreStoredBoundedAndEditable() {
        var catalog = http.getForEntity("/api/development/workflow-task-catalog", List.class).getBody();
        assertThat((List<?>) catalog).hasSize(7);
        assertThat(catalog.toString()).contains("REPOSITORY", "PR", "ISSUE", "CI", "CHECK", "RECHECK", "SUMMARY");

        var created = createTemplate("只读 PR 检查", ids("REPOSITORY", "PR", "CHECK", "SUMMARY"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map template = created.getBody();
        assertThat(template).containsEntry("name", "只读 PR 检查").containsEntry("revision", 1);
        var tasks = (List<Map>) template.get("tasks");
        assertThat(tasks).hasSize(4);
        assertThat(tasks.get(1).get("requires")).isEqualTo(List.of("REPOSITORY"));
        assertThat(tasks.get(2).get("after").toString()).contains("REPOSITORY", "PR");
        assertThat(tasks.get(3).get("id")).isEqualTo("SUMMARY");
        String id = template.get("id").toString();
        assertThat(http.getForEntity("/api/development/workflow-templates/" + id, Map.class).getBody()).containsEntry("id", id);
        assertThat((List<?>) http.getForEntity("/api/development/repositories/" + repo + "/workflow-templates", List.class).getBody()).hasSize(1);

        var renamed = http.exchange("/api/development/workflow-templates/" + id, HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "name", "只读 PR 检查（改名）"), headers()), Map.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody()).containsEntry("revision", 2).containsEntry("name", "只读 PR 检查（改名）");
        assertThat((List<?>) renamed.getBody().get("tasks")).hasSize(4);
        assertThat(http.exchange("/api/development/workflow-templates/" + id, HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "name", "过期修订"), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        for (int i = 0; i < 9; i++)
            assertThat(createTemplate("补充模板 " + i, ids("REPOSITORY", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createTemplate("第十一个", ids("REPOSITORY", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/api/development/workflow-templates/" + id, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity("/api/development/workflow-templates/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(createTemplate("删除后可新建", ids("REPOSITORY", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(null, headers()), String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_templates", Long.class)).isEqualTo(0);
    }

    @Test void invalidTemplatesAndMaterialInputsAreRejected() {
        assertThat(createTemplate("未知任务", ids("REPOSITORY", "GHOST", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTemplate("重复任务", ids("REPOSITORY", "PR", "PR", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTemplate("缺仓库概览", ids("PR", "CHECK", "SUMMARY")).getBody().toString()).contains("REPOSITORY");
        assertThat(createTemplate("缺前置 PR", ids("REPOSITORY", "CI", "SUMMARY")).getBody().toString()).contains("PR");
        assertThat(createTemplate("缺核对", ids("REPOSITORY", "PR", "RECHECK", "SUMMARY")).getBody().toString()).contains("CHECK");
        assertThat(createTemplate("缺综合报告", ids("REPOSITORY", "PR", "CHECK")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTemplate("报告不在末尾", ids("REPOSITORY", "PR", "SUMMARY", "CHECK")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTemplate("只有报告", ids("SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createTemplate(" ", ids("REPOSITORY", "SUMMARY")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/development/repositories/" + repo + "/workflow-templates", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "自依赖", "tasks", List.of(Map.of("id", "REPOSITORY"),
                    Map.of("id", "PR", "requires", List.of("PR")), Map.of("id", "CHECK"), Map.of("id", "SUMMARY"))), headers()), Map.class)
            .getBody().toString()).contains("依赖");
        assertThat(http.exchange("/api/development/repositories/" + repo + "/workflow-templates", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "缺失引用", "tasks", List.of(Map.of("id", "REPOSITORY"),
                    Map.of("id", "PR", "requires", List.of("GHOST")), Map.of("id", "CHECK"), Map.of("id", "SUMMARY"))), headers()), Map.class)
            .getBody().toString()).contains("GHOST");
        assertThat(http.exchange("/api/development/repositories/" + repo + "/workflow-templates", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "顺序矛盾", "tasks", List.of(Map.of("id", "PR", "requires", List.of("REPOSITORY")),
                    Map.of("id", "REPOSITORY"), Map.of("id", "CHECK"), Map.of("id", "SUMMARY"))), headers()), Map.class)
            .getBody().toString()).contains("顺序");

        var template = createTemplate("只读组合", ids("REPOSITORY", "PR", "CHECK", "SUMMARY")).getBody();
        assertThat(http.postForEntity("/api/development/repositories/" + repo + "/workflow-templates/" + template.get("id") + "/plans",
            new HttpEntity<>(Map.of("prNumber", 7, "ciReportId", UUID.randomUUID()), headers()), String.class).getBody()).contains("CI");
        assertThat(http.postForEntity("/api/development/repositories/" + repo + "/workflow-templates/" + template.get("id") + "/plans",
            new HttpEntity<>(Map.of("prNumber", 7, "issueNumber", 2), headers()), String.class).getBody()).contains("ISSUE");
        assertThat(http.postForEntity("/api/development/repositories/" + UUID.randomUUID() + "/workflow-templates/" + template.get("id") + "/plans",
            new HttpEntity<>(Map.of("prNumber", 7), headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void customPlanRunsItsFrozenTasksAndTemplateEditsDoNotAffectIt() {
        var created = createTemplate("只读 PR 检查", ids("REPOSITORY", "PR", "CHECK", "SUMMARY")).getBody();
        Map plan = templatePlan(created.get("id"), Map.of("prNumber", 7));
        assertThat((List<?>) plan.get("tasks")).hasSize(4);
        assertThat(plan.get("notice").toString()).contains("只读 PR 检查");
        Map run = execute(plan);
        assertThat(((List<Map>) run.get("tasks")).stream().map(task -> task.get("id")).toList())
            .containsExactly("REPOSITORY", "PR", "CHECK", "SUMMARY");
        assertThat(step(run, "PR").get("state")).isEqualTo("SUCCEEDED");
        assertThat(step(run, "SUMMARY").get("state")).isEqualTo("SUCCEEDED");
        assertThat(run.get("state")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        Map data = (Map) run.get("data");
        assertThat(((Map) data.get("pr")).get("pull")).isNotNull();
        assertThat(data.get("ci")).isNull();

        // Edit the template: the next plan uses the new tasks, the old frozen plan would not.
        var updated = http.exchange("/api/development/workflow-templates/" + created.get("id"), HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", 1, "name", "PR 与 CI 检查", "tasks",
                ids("REPOSITORY", "PR", "CI", "CHECK", "SUMMARY")), headers()), Map.class).getBody();
        Map secondPlan = templatePlan(created.get("id"), Map.of("prNumber", 7));
        assertThat((List<?>) secondPlan.get("tasks")).hasSize(5);
        Map secondRun = execute(secondPlan);
        assertThat(step(secondRun, "CI").get("state")).isEqualTo("SUCCEEDED");

        // A plan created before an edit keeps executing its frozen task list.
        Map frozenPlan = templatePlan(created.get("id"), Map.of("prNumber", 7));
        http.exchange("/api/development/workflow-templates/" + created.get("id"), HttpMethod.PUT,
            new HttpEntity<>(Map.of("revision", ((Number) updated.get("revision")).intValue(), "name", "回到只读",
                "tasks", ids("REPOSITORY", "PR", "CHECK", "SUMMARY")), headers()), Map.class);
        Map frozenRun = execute(frozenPlan);
        assertThat(((List<Map>) frozenRun.get("tasks")).stream().map(task -> task.get("id")).toList())
            .containsExactly("REPOSITORY", "PR", "CI", "CHECK", "SUMMARY");
        assertThat(step(frozenRun, "CI").get("state")).isEqualTo("SUCCEEDED");
    }

    @Test void fixedWorkflowTemplateRemainsAvailable() {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflows/plans",
            new HttpEntity<>(Map.of("prNumber", 7), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var tasks = (List<Map>) response.getBody().get("tasks");
        assertThat(tasks).hasSize(7);
        assertThat(tasks.getFirst().get("id")).isEqualTo("REPOSITORY");
        assertThat(tasks.getLast().get("id")).isEqualTo("SUMMARY");
    }

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(fixtureWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.equals("/repos/acme/service")) {
                    data = Map.of("full_name", "acme/service", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 1);
                } else if (path.endsWith("/pulls/7")) data = Map.of("number", 7, "title", "Change", "body", "Fixes #2", "state", "open", "draft", false, "changed_files", 0, "additions", 0, "deletions", 0, "base", Map.of("sha", BASE, "repo", Map.of("full_name", "acme/service")), "head", Map.of("sha", HEAD));
                else if (path.contains("/compare/")) data = Map.of("base_commit", Map.of("sha", BASE), "merge_base_commit", Map.of("sha", BASE), "files", List.of());
                else if (path.endsWith("/check-runs")) data = Map.of("total_count", 1, "check_runs", List.of(Map.of("id", 91, "name", "Build", "status", "completed", "conclusion", "failure", "head_sha", HEAD)));
                else if (path.endsWith("/status")) data = Map.of("sha", HEAD, "total_count", 0, "statuses", List.of());
                else if (path.endsWith("/issues/2")) data = Map.of("number", 2, "title", "Build failure", "body", "Build fails", "state", "open", "updated_at", "2026-10-14T00:00:00Z", "labels", List.of(Map.of("name", "bug")), "assignees", List.of(), "comments", 0);
                else if (path.endsWith("/commits/main")) data = Map.of("sha", HEAD);
                else if (path.endsWith("/commits")) data = List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Build change")));
                else if (path.endsWith("/contributors")) data = List.of(Map.of("login", "alice", "contributions", 2));
                else if (path.endsWith("/actions/runs")) {
                    if (exchange.getRequestURI().getRawQuery().contains("head_sha=")) data = Map.of("workflow_runs", List.of(run(9, HEAD)));
                    else data = Map.of("workflow_runs", List.of());
                } else if (path.contains("/actions/runs/9") && !path.endsWith("/jobs")) data = run(9, HEAD);
                else if (path.endsWith("/jobs")) data = Map.of("jobs", List.of(job(91, 9, HEAD)));
                else if (path.endsWith("/actions/jobs/91")) data = job(91, 9, HEAD);
                else if (path.endsWith("/logs")) {
                    byte[] bytes = "[ERROR] COMPILATION ERROR\n[ERROR] Foo.java:[17,4] cannot find symbol".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(bytes); }
                    return;
                } else if (path.endsWith("/issues") || path.endsWith("/pulls")) data = List.of();
                else { status = 404; data = Map.of(); }
                byte[] bytes = JSON.writeValueAsBytes(data);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    static Map run(long id, String sha) { return Map.of("id", id, "name", "Verify", "run_attempt", 1, "head_sha", sha, "head_branch", "main", "status", "completed", "conclusion", "failure", "repository", Map.of("full_name", "acme/service")); }
    static Map job(long id, long run, String sha) { return Map.of("id", id, "run_id", run, "run_attempt", 1, "head_sha", sha, "name", "Build", "status", "completed", "conclusion", "failure", "steps", List.of()); }
}
