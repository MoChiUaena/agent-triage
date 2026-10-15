package io.github.mochiuaena.triage.api;

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
    "spring.datasource.url=jdbc:h2:mem:github-ci;DB_CLOSE_DELAY=-1", "triage.github.token=fixture-ci-token"
})
class GithubCiApiTest {
    static final String SHA = "a".repeat(40);
    static final AtomicBoolean changed = new AtomicBoolean(), denied = new AtomicBoolean();
    static final AtomicInteger logCalls = new AtomicInteger();
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @Test void reportsUseTheSelectedRunJobAndRedactedEvidenceAndSupportExplicitPastedLogs() {
        var created = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String repository = ((Map<?, ?>) created.getBody().get("registration")).get("id").toString();
        String root = "/api/development/repositories/" + repository + "/ci";
        var context = http.getForEntity(root + "/runs/9", Map.class);
        assertThat(context.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(context.getBody().get("headSha")).isEqualTo(SHA);
        assertThat((List<?>) context.getBody().get("jobs")).hasSize(1);
        var input = new HashMap<String,Object>(Map.of("runId", 9, "attempt", 1, "jobId", 91, "headSha", SHA));
        assertThat(http.postForEntity(root + "/reports", input, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var response = http.postForEntity(root + "/reports", new HttpEntity<>(input, headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("source", "GITHUB").containsEntry("headSha", SHA);
        var analysis = (Map<?, ?>) response.getBody().get("analysis");
        assertThat(analysis.get("category")).isEqualTo("COMPILATION_FAILURE");
        assertThat(analysis.get("status")).isEqualTo("OBSERVED");
        assertThat((List<?>) analysis.get("evidence")).isNotEmpty();
        assertThat(analysis.toString()).contains("17", "Foo.java").doesNotContain("x".repeat(40));
        String report = response.getBody().get("id").toString();
        assertThat(http.getForObject("/api/development/ci/reports/" + report, Map.class)).isEqualTo(response.getBody());
        assertThat(jdbc.queryForObject("SELECT payload FROM ci_reports WHERE id=?", String.class, report))
            .doesNotContain("x".repeat(40));
        changed.set(true);
        assertThat(http.postForEntity(root + "/reports", new HttpEntity<>(input, headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        changed.set(false); denied.set(true);
        assertThat(http.postForEntity(root + "/reports", new HttpEntity<>(input, headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        int previousCalls = logCalls.get();
        input.put("logText", "[ERROR] Tests run: 4, Failures: 1, Errors: 0\norg.opentest4j.AssertionFailedError: expected 3 but was 2");
        var pasted = http.postForEntity(root + "/reports", new HttpEntity<>(input, headers()), Map.class);
        assertThat(pasted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pasted.getBody()).containsEntry("source", "PASTED");
        assertThat(((Map<?, ?>) pasted.getBody().get("analysis")).get("category")).isEqualTo("TEST_FAILURE");
        assertThat(pasted.getBody().get("notice").toString()).contains("未验证");
        assertThat(logCalls).hasValue(previousCalls);
        assertThat((List<?>) http.getForObject(root + "/reports", List.class)).hasSize(2);
    }
    static HttpHeaders headers() { var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Development", "1"); return headers; }
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200;
                String body;
                if (path.endsWith("/logs")) {
                    logCalls.incrementAndGet(); status = denied.get() ? 403 : 200;
                    body = "2026-10-13T00:00:10Z AssertionError in passing fixture\n2026-10-13T00:01:10Z [ERROR] COMPILATION ERROR :\n2026-10-13T00:01:11Z [ERROR] /repo/src/Foo.java:[17,4] cannot find symbol\n2026-10-13T00:01:12Z GITHUB_TOKEN=ghp_" + "x".repeat(40) + "\n2026-10-13T00:01:13Z ##[error]Process completed with exit code 1.";
                } else if (path.endsWith("/jobs/91")) {
                    body = job();
                } else if (path.contains("/actions/runs/9") && !path.endsWith("/jobs")) {
                    body = "{\"id\":9,\"name\":\"Verify\",\"run_attempt\":1,\"head_sha\":\"" + (changed.get() ? "b".repeat(40) : SHA)
                        + "\",\"head_branch\":\"main\",\"status\":\"completed\",\"conclusion\":\"failure\",\"repository\":{\"full_name\":\"acme/service\"}}";
                } else if (path.endsWith("/jobs")) {
                    body = "{\"jobs\":[" + job() + "]}";
                } else if (path.equals("/repos/acme/service")) {
                    body = "{\"full_name\":\"acme/service\",\"default_branch\":\"main\",\"private\":false,\"archived\":false,\"open_issues_count\":0}";
                } else body = path.endsWith("/actions/runs") ? "{\"workflow_runs\":[]}" : "[]";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }); server.start(); return server;
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }
    static String job() {
        return "{\"id\":91,\"run_id\":9,\"run_attempt\":1,\"head_sha\":\"" + SHA + "\",\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"failure\",\"steps\":[{\"number\":2,\"name\":\"Compile\",\"conclusion\":\"failure\",\"started_at\":\"2026-10-13T00:01:00Z\",\"completed_at\":\"2026-10-13T00:02:00Z\"}]}";
    }
    @AfterAll static void stop() { server.stop(0); }
}
