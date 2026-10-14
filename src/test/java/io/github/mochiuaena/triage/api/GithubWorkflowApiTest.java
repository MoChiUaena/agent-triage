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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:github-workflow;DB_CLOSE_DELAY=-1", "triage.github.token=fixture-workflow-key",
    "triage.development.workflow-timeout=60s"
})
class GithubWorkflowApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), OLD = "c".repeat(40);
    static final AtomicBoolean changed = new AtomicBoolean(), deniedLog = new AtomicBoolean(), slowRepository = new AtomicBoolean();
    static final AtomicInteger logCalls = new AtomicInteger(), runListCalls = new AtomicInteger();
    static final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(0));
    static final ExecutorService fixtureWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("workflow-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @BeforeEach void reset() {
        gate.get().countDown(); gate.set(new CountDownLatch(0)); changed.set(false); deniedLog.set(false); slowRepository.set(false); logCalls.set(0); runListCalls.set(0);
        jdbc.update("DELETE FROM github_repositories");
    }
    @Test void aFrozenPlanExecutesRealDependentAnalysesAndPersistsTheCombinedReport() {
        String repo = register(), root = root(repo);
        assertThat(http.postForEntity(root + "/plans", Map.of("prNumber", 7), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map plan = plan(repo, Map.of("prNumber", 7));
        assertThat((Map) plan.get("pull")).containsEntry("headSha", HEAD).containsEntry("baseSha", BASE);
        assertThat((List<?>) plan.get("tasks")).hasSize(7);
        var response = http.postForEntity(root, new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map result = finished(response.getBody().get("id").toString());
        assertThat(step(result, "PR").get("state")).isEqualTo("SUCCEEDED");
        assertThat(step(result, "CI").get("state")).isEqualTo("SUCCEEDED");
        assertThat(step(result, "SUMMARY").get("state")).isEqualTo("SUCCEEDED");
        Map data = (Map) result.get("data");
        assertThat((Map) ((Map) data.get("pr")).get("pull")).containsEntry("headSha", HEAD);
        assertThat((Map) ((Map) data.get("ci")).get("report")).containsEntry("headSha", HEAD).containsEntry("source", "GITHUB");
        assertThat(data.get("summary").toString()).contains("ATTENTION_REQUIRED");
        assertThat(result.toString()).doesNotContain("fixture-workflow-key", "fixture-private-password");
        String id = result.get("id").toString();
        assertThat(jdbc.queryForObject("SELECT payload FROM development_workflows WHERE id=?", String.class, id)).doesNotContain("fixture-workflow-key");
        assertThat((List<?>) http.getForObject(root, List.class)).hasSize(1);
        assertThat(http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity("/api/development/workflows/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void changedPrSkipsItsDependentsButStillProducesAnIncompleteSummary() {
        String repo = register(); Map plan = plan(repo, Map.of("prNumber", 7)); changed.set(true);
        Map result = execute(repo, plan);
        assertThat(step(result, "PR").get("state")).isEqualTo("FAILED");
        assertThat(step(result, "CI").get("state")).isEqualTo("SKIPPED");
        assertThat(step(result, "ISSUE").get("state")).isEqualTo("SKIPPED");
        assertThat(step(result, "SUMMARY").get("state")).isEqualTo("SUCCEEDED");
        assertThat(logCalls).hasValue(0);
        assertThat(((Map) ((Map) result.get("data")).get("summary")).get("verdict")).isIn("REPLAN_REQUIRED", "INCOMPLETE");
    }
    @Test void unavailableLogsDoNotSkipIndependentIssueAnalysisOrCountAsSuccess() {
        String repo = register(); deniedLog.set(true);
        Map result = execute(repo, plan(repo, Map.of("prNumber", 7, "issueNumber", 2)));
        assertThat(step(result, "CI").get("state")).isEqualTo("FAILED");
        assertThat(step(result, "ISSUE").get("state")).isIn("SUCCEEDED", "PARTIAL");
        assertThat(((Map) ((Map) result.get("data")).get("summary")).get("verdict")).isEqualTo("INCOMPLETE");
        assertThat(result.get("state")).isEqualTo("COMPLETED_WITH_GAPS");
    }
    @Test void oldCiEvidenceIsReplacedByAtMostOneBoundedRecheckOfTheSelectedCommit() {
        String repo = register();
        var old = http.postForEntity("/api/development/repositories/" + repo + "/ci/reports", new HttpEntity<>(Map.of("runId", 19, "attempt", 1, "jobId", 191, "headSha", OLD, "logText", "[ERROR] Tests run: 3, Failures: 1, Errors: 0"), headers()), Map.class);
        assertThat(old.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map result = execute(repo, plan(repo, Map.of("prNumber", 7, "ciReportId", old.getBody().get("id"))));
        Map data = (Map) result.get("data");
        assertThat((Map) ((Map) data.get("ci")).get("report")).containsEntry("headSha", HEAD).containsEntry("source", "GITHUB");
        assertThat(data.get("checks").toString()).contains("CI_HEAD_MISMATCH", "RESOLVED");
        assertThat(step(result, "RECHECK").get("state")).isIn("SUCCEEDED", "PARTIAL");
        assertThat(runListCalls.get()).isLessThanOrEqualTo(2);
        assertThat(logCalls).hasValue(1);
    }
    @Test void cancellationIsPersistentAndLateResultsCannotRewriteTheTerminalRun() {
        String repo = register(); Map plan = plan(repo, Map.of("prNumber", 7)); gate.set(new CountDownLatch(1)); slowRepository.set(true);
        var response = http.postForEntity(root(repo), new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); String id = response.getBody().get("id").toString();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(step(http.getForObject("/api/development/workflows/" + id, Map.class), "REPOSITORY").get("state")).isEqualTo("RUNNING"));
        assertThat(http.postForEntity("/api/development/workflows/" + id + "/cancel", new HttpEntity<>(headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        gate.get().countDown();
        Map result = finished(id);
        assertThat(result.get("state")).isEqualTo("CANCELLED");
        assertThat(step(result, "PR").get("state")).isEqualTo("SKIPPED");
        assertThat(logCalls).hasValue(0);
        assertThat(http.getForObject("/api/development/workflows/" + id, Map.class).get("state")).isEqualTo("CANCELLED");
    }
    @Test void aPlanCannotBeExecutedForAnotherRepository() {
        String repo = register(); Map plan = plan(repo, Map.of("prNumber", 7));
        assertThat(http.postForEntity(root(UUID.randomUUID().toString()), new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), String.class).getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);
    }
    String register() {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return ((Map) response.getBody().get("registration")).get("id").toString();
    }
    Map plan(String repo, Map input) { var response = http.postForEntity(root(repo) + "/plans", new HttpEntity<>(input, headers()), Map.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); return response.getBody(); }
    Map execute(String repo, Map plan) { var response = http.postForEntity(root(repo), new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), Map.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); return finished(response.getBody().get("id").toString()); }
    Map finished(String id) {
        var result = new AtomicReference<Map>();
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(25)).until(() -> { Map value = http.getForObject("/api/development/workflows/" + id, Map.class); result.set(value); return !Set.of("PLANNED", "RUNNING").contains(value.get("state")); });
        return result.get();
    }
    static Map step(Map result, String id) { return (Map) ((List<?>) result.get("tasks")).stream().map(value -> (Map) value).filter(value -> value.get("id").equals(id)).findFirst().orElseThrow(); }
    static String root(String repo) { return "/api/development/repositories/" + repo + "/workflows"; }
    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(fixtureWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.equals("/repos/acme/service")) {
                    if (slowRepository.get()) try { gate.get().await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    data = Map.of("full_name", "acme/service", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 1);
                } else if (path.endsWith("/pulls/7")) data = Map.of("number", 7, "title", "Change", "body", "Fixes #2", "state", "open", "draft", false, "changed_files", 0, "additions", 0, "deletions", 0, "base", Map.of("sha", BASE, "repo", Map.of("full_name", "acme/service")), "head", Map.of("sha", changed.get() ? "d".repeat(40) : HEAD));
                else if (path.contains("/compare/")) data = Map.of("base_commit", Map.of("sha", BASE), "merge_base_commit", Map.of("sha", BASE), "files", List.of());
                else if (path.endsWith("/check-runs")) data = Map.of("total_count", 1, "check_runs", List.of(Map.of("id", 91, "name", "Build", "status", "completed", "conclusion", "failure", "head_sha", HEAD)));
                else if (path.endsWith("/status")) data = Map.of("sha", HEAD, "total_count", 0, "statuses", List.of());
                else if (path.endsWith("/issues/2")) data = Map.of("number", 2, "title", "Build failure", "body", "Build fails\npassword=fixture-private-password", "state", "open", "updated_at", "2026-10-14T00:00:00Z", "labels", List.of(Map.of("name", "bug")), "assignees", List.of(), "comments", 0);
                else if (path.endsWith("/commits/main")) data = Map.of("sha", HEAD);
                else if (path.endsWith("/commits")) data = List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Build change")));
                else if (path.endsWith("/contributors")) data = List.of(Map.of("login", "alice", "contributions", 2));
                else if (path.endsWith("/actions/runs")) {
                    if (exchange.getRequestURI().getRawQuery().contains("head_sha=")) { runListCalls.incrementAndGet(); data = Map.of("workflow_runs", List.of(run(9, HEAD))); }
                    else data = Map.of("workflow_runs", List.of());
                } else if (path.contains("/actions/runs/19") && !path.endsWith("/jobs")) data = run(19, OLD);
                else if (path.contains("/actions/runs/9") && !path.endsWith("/jobs")) data = run(9, HEAD);
                else if (path.endsWith("/jobs")) data = Map.of("jobs", List.of(job(91, 9, HEAD)));
                else if (path.endsWith("/actions/jobs/91")) data = job(91, 9, HEAD);
                else if (path.endsWith("/actions/jobs/191")) data = job(191, 19, OLD);
                else if (path.endsWith("/logs")) { logCalls.incrementAndGet(); status = deniedLog.get() ? 403 : 200; byte[] bytes = "[ERROR] COMPILATION ERROR\n[ERROR] Foo.java:[17,4] cannot find symbol\npassword=fixture-private-password".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); } return; }
                else if (path.endsWith("/issues") || path.endsWith("/pulls")) data = List.of();
                else { status = 404; data = Map.of(); }
                byte[] bytes = JSON.writeValueAsBytes(data); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }); server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    static Map run(long id, String sha) { return Map.of("id", id, "name", "Verify", "run_attempt", 1, "head_sha", sha, "head_branch", "main", "status", "completed", "conclusion", "failure", "repository", Map.of("full_name", "acme/service")); }
    static Map job(long id, long run, String sha) { return Map.of("id", id, "run_id", run, "run_attempt", 1, "head_sha", sha, "name", "Build", "status", "completed", "conclusion", "failure", "steps", List.of()); }
    @AfterAll static void stop() { gate.get().countDown(); server.stop(0); fixtureWorkers.shutdownNow(); }
}
