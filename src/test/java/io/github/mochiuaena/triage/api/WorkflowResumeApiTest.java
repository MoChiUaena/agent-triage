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
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:workflow-resume;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-resume-key", "triage.development.workflow-timeout=60s"
})
class WorkflowResumeApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40);
    static final AtomicBoolean noIssueReference = new AtomicBoolean();
    static final AtomicBoolean driftHead = new AtomicBoolean();
    /** 0 = runs readable; 1 = first read fails with 503; 2 = every read fails with 503. */
    static final AtomicInteger failRuns = new AtomicInteger();
    static final ExecutorService fixtureWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("resume-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    String repo;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @BeforeEach void reset() {
        noIssueReference.set(false); driftHead.set(false); failRuns.set(0);
        jdbc.update("DELETE FROM development_workflows");
        jdbc.update("DELETE FROM github_repositories");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map) response.getBody().get("registration")).get("id").toString();
    }
    @AfterAll static void stop() { server.stop(0); fixtureWorkers.shutdownNow(); }

    private Map plan(Map input) {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflows/plans", new HttpEntity<>(input, headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map execute(Map plan) {
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflows",
            new HttpEntity<>(Map.of("planId", plan.get("id")), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return waitFor(response.getBody().get("id").toString(), "WAITING");
    }

    private Map waitFor(String id, String... states) {
        var result = new AtomicReference<Map>();
        await().pollInterval(Duration.ofMillis(100)).atMost(Duration.ofSeconds(25)).until(() -> {
            Map value = http.getForObject("/api/development/workflows/" + id, Map.class);
            result.set(value);
            boolean terminal = !Set.of("PLANNED", "RUNNING", "WAITING").contains(value.get("state"));
            return terminal || (states.length > 0 && Set.of((Object[]) states).contains(value.get("state")));
        });
        return result.get();
    }

    private Map resume(String id, Map body) {
        return http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(body, headers()), Map.class).getBody();
    }

    static Map step(Map result, String id) {
        return (Map) ((List<?>) result.get("tasks")).stream().map(value -> (Map) value).filter(value -> value.get("id").equals(id)).findFirst().orElseThrow();
    }

    @Test void missingIssueMaterialWaitsThenResumesWithTheSupplement() {
        noIssueReference.set(true);
        Map waiting = execute(plan(Map.of("prNumber", 7)));
        assertThat(waiting.get("state")).isEqualTo("WAITING");
        assertThat(waiting.get("message").toString()).contains("等待人工处理");
        assertThat(step(waiting, "ISSUE").get("state")).isEqualTo("WAITING");
        assertThat(step(waiting, "ISSUE").get("errorCode")).isEqualTo("WAIT_MATERIAL");
        assertThat(step(waiting, "PR").get("state")).isEqualTo("SUCCEEDED");
        assertThat(step(waiting, "CHECK").get("state")).isEqualTo("PENDING");
        assertThat(step(waiting, "SUMMARY").get("state")).isEqualTo("PENDING");
        assertThat(((Map) waiting.get("data")).get("pr")).isNotNull();
        assertThat(waiting.get("resumes")).isEqualTo(0);
        String id = waiting.get("id").toString();

        var resumed = http.postForEntity("/api/development/workflows/" + id + "/resume",
            new HttpEntity<>(Map.of("issueNumber", 2), headers()), Map.class);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map) resumed.getBody()).containsEntry("resumes", 1);
        Map done = waitFor(id);
        assertThat(done.get("state")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        assertThat(step(done, "ISSUE").get("state")).isIn("SUCCEEDED", "PARTIAL");
        assertThat(step(done, "PR").get("state")).isEqualTo("SUCCEEDED");
        assertThat(step(done, "SUMMARY").get("state")).isEqualTo("SUCCEEDED");
        Map data = (Map) done.get("data");
        assertThat((Map) ((Map) data.get("issue")).get("issue")).containsEntry("number", 2);
        assertThat(data.get("pr")).isNotNull();
        assertThat(done.get("resumes")).isEqualTo(1);
    }

    @Test void retryableReadFailuresResumeUntilTheBoundedLimit() {
        failRuns.set(2);
        Map first = execute(plan(Map.of("prNumber", 7)));
        assertThat(first.get("state")).isEqualTo("WAITING");
        assertThat(step(first, "CI").get("errorCode")).isEqualTo("WAIT_RETRY");
        String id = first.get("id").toString();

        Map second = waitFor(resume(id, Map.of()).get("id").toString(), "WAITING", "COMPLETED", "COMPLETED_WITH_GAPS", "FAILED");
        assertThat(second.get("state")).isEqualTo("WAITING");
        assertThat(second.get("resumes")).isEqualTo(1);

        Map third = waitFor(resume(id, Map.of()).get("id").toString(), "WAITING", "COMPLETED", "COMPLETED_WITH_GAPS", "FAILED");
        assertThat(third.get("state")).isEqualTo("COMPLETED_WITH_GAPS");
        assertThat(step(third, "CI").get("state")).isEqualTo("FAILED");
        assertThat(third.get("resumes")).isEqualTo(2);
        assertThat(http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(Map.of(), headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void resumeGuardsStateMaterialAndTargetVersion() {
        noIssueReference.set(true);
        Map waiting = execute(plan(Map.of("prNumber", 7)));
        String id = waiting.get("id").toString();
        var noMaterial = http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(Map.of(), headers()), String.class);
        assertThat(noMaterial.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noMaterial.getBody()).contains("Issue");
        driftHead.set(true);
        var drifted = http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(Map.of("issueNumber", 2), headers()), String.class);
        assertThat(drifted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(drifted.getBody()).contains("目标已变化").contains("重建计划");
        assertThat(http.getForEntity("/api/development/workflows/" + id, Map.class).getBody()).containsEntry("state", "WAITING");
        driftHead.set(false);
        assertThat(resume(id, Map.of("issueNumber", 2)).get("state")).isEqualTo("PLANNED");
        Map done = waitFor(id);
        assertThat(done.get("state")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        assertThat(http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(Map.of(), headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void waitingWorkflowCanBeCancelledAndFreesTheAdmissionSlot() {
        noIssueReference.set(true);
        Map waiting = execute(plan(Map.of("prNumber", 7)));
        String id = waiting.get("id").toString();
        var cancelled = http.postForEntity("/api/development/workflows/" + id + "/cancel", new HttpEntity<>(Map.of(), headers()), Map.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("state", "CANCELLED");
        assertThat(step(cancelled.getBody(), "ISSUE").get("state")).isEqualTo("SKIPPED");
        assertThat(step(cancelled.getBody(), "PR").get("state")).isEqualTo("SUCCEEDED");
        assertThat(http.postForEntity("/api/development/workflows/" + id + "/resume", new HttpEntity<>(Map.of("issueNumber", 2), headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);

        // The waiting workflow no longer holds a slot: a full workflow still runs to completion.
        Map fresh = plan(Map.of("prNumber", 7, "issueNumber", 2));
        var response = http.postForEntity("/api/development/repositories/" + repo + "/workflows",
            new HttpEntity<>(Map.of("planId", fresh.get("id")), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map done = waitFor(response.getBody().get("id").toString());
        assertThat(done.get("state")).isIn("COMPLETED", "COMPLETED_WITH_GAPS");
        assertThat(step(done, "SUMMARY").get("state")).isEqualTo("SUCCEEDED");
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
                } else if (path.endsWith("/pulls/7")) data = Map.of("number", 7, "title", "Change",
                    "body", noIssueReference.get() ? "Refactors internals" : "Fixes #2", "state", "open", "draft", false,
                    "changed_files", 0, "additions", 0, "deletions", 0,
                    "base", Map.of("sha", BASE, "repo", Map.of("full_name", "acme/service")),
                    "head", Map.of("sha", driftHead.get() ? "d".repeat(40) : HEAD));
                else if (path.contains("/compare/")) data = Map.of("base_commit", Map.of("sha", BASE), "merge_base_commit", Map.of("sha", BASE), "files", List.of());
                else if (path.endsWith("/check-runs")) data = Map.of("total_count", 1, "check_runs", List.of(Map.of("id", 91, "name", "Build", "status", "completed", "conclusion", "failure", "head_sha", HEAD)));
                else if (path.endsWith("/status")) data = Map.of("sha", HEAD, "total_count", 0, "statuses", List.of());
                else if (path.endsWith("/issues/2")) data = Map.of("number", 2, "title", "Build failure", "body", "Build fails", "state", "open", "updated_at", "2026-10-14T00:00:00Z", "labels", List.of(Map.of("name", "bug")), "assignees", List.of(), "comments", 0);
                else if (path.endsWith("/commits/main")) data = Map.of("sha", HEAD);
                else if (path.endsWith("/commits")) data = List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Build change")));
                else if (path.endsWith("/contributors")) data = List.of(Map.of("login", "alice", "contributions", 2));
                else if (path.endsWith("/actions/runs")) {
                    if (exchange.getRequestURI().getRawQuery().contains("head_sha=") && failRuns.get() > 0) {
                        byte[] bytes = JSON.writeValueAsBytes(Map.of("message", "unavailable"));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(503, bytes.length);
                        try (var out = exchange.getResponseBody()) { out.write(bytes); }
                        return;
                    }
                    data = exchange.getRequestURI().getRawQuery().contains("head_sha=")
                        ? Map.of("workflow_runs", List.of(run(9, HEAD))) : Map.of("workflow_runs", List.of());
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
