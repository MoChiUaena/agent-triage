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
    "spring.datasource.url=jdbc:h2:mem:github-pr;DB_CLOSE_DELAY=-1", "triage.github.token=fixture-pr-token"
})
class GithubPrApiTest {
    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), MERGE_BASE = "c".repeat(40);
    static final AtomicBoolean changed = new AtomicBoolean(), missingPatch = new AtomicBoolean(), deniedChecks = new AtomicBoolean(), wrongCheck = new AtomicBoolean();
    static final AtomicBoolean argumentSecret = new AtomicBoolean();
    static final AtomicInteger pullReads = new AtomicInteger();
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @BeforeEach void reset() {
        changed.set(false); missingPatch.set(false); deniedChecks.set(false); wrongCheck.set(false); argumentSecret.set(false); pullReads.set(0);
        jdbc.update("DELETE FROM github_repositories");
    }
    @Test void freezesDiffVersionsAndPersistsLineEvidenceWithCommitChecksAndExplicitIssueReferences() {
        String repo = register(), root = "/api/development/repositories/" + repo + "/pr";
        var preview = http.getForEntity(root + "/pulls/7", Map.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody()).containsEntry("headSha", HEAD).containsEntry("baseSha", BASE);
        var input = Map.of("number", 7, "headSha", HEAD, "baseSha", BASE);
        assertThat(http.postForEntity(root + "/reports", input, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var response = http.postForEntity(root + "/reports", new HttpEntity<>(input, headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var report = response.getBody();
        assertThat(report).containsEntry("mergeBaseSha", MERGE_BASE).containsEntry("source", "GITHUB_RULES");
        var finding = (Map) ((List<?>) report.get("findings")).getFirst();
        assertThat(finding).containsEntry("file", "src/main/java/Security.java").containsEntry("line", 11).containsEntry("side", "RIGHT");
        assertThat(finding.get("url").toString()).contains(HEAD, "#L11");
        assertThat(finding.get("excerpt").toString()).contains("permitAll").doesNotContain("s".repeat(40));
        assertThat(((Map<?, ?>) report.get("ci")).get("state")).isEqualTo("OBSERVED_SUCCESS");
        assertThat((List<?>) report.get("issues")).hasSize(1);
        String id = report.get("id").toString();
        assertThat(http.getForObject("/api/development/pr/reports/" + id, Map.class)).isEqualTo(report);
        assertThat(jdbc.queryForObject("SELECT payload FROM pr_reports WHERE id=?", String.class, id)).doesNotContain("s".repeat(40));
        assertThat((List<?>) http.getForObject(root + "/reports", List.class)).hasSize(1);
        assertThat(http.exchange("/api/development/repositories/" + repo + "?confirmId=" + repo, HttpMethod.DELETE, new HttpEntity<>(headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity("/api/development/pr/reports/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void refusesAChangedPrWithoutSavingAMixedVersionReport() {
        String repo = register(); changed.set(true);
        var response = create(repo);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pr_reports", Integer.class)).isZero();
    }
    @Test void missingPatchAndUnavailableChecksRemainVisibleWithoutInventingApproval() {
        String repo = register(); missingPatch.set(true); deniedChecks.set(true);
        var response = create(repo);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) response.getBody().get("findings")).isEmpty();
        assertThat(response.getBody().get("gaps").toString()).contains("Security.java");
        assertThat(((Map<?, ?>) response.getBody().get("ci")).get("state")).isEqualTo("INCOMPLETE");
    }
    @Test void checksFromAnotherCommitCannotCountAsSuccessfulValidation() {
        String repo = register(); wrongCheck.set(true);
        var response = create(repo);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var ci = (Map<?, ?>) response.getBody().get("ci");
        assertThat(ci.get("state")).isEqualTo("INCOMPLETE");
        assertThat((List<?>) ci.get("checks")).isEmpty();
    }
    @Test void sourceArgumentCredentialsAreRedactedInPersistedReports() {
        String repo = register(); argumentSecret.set(true);
        var response = create(repo);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().toString()).doesNotContain("fixture-pr-argument-secret");
        assertThat(jdbc.queryForObject("SELECT payload FROM pr_reports WHERE id=?", String.class, response.getBody().get("id")))
            .doesNotContain("fixture-pr-argument-secret");
    }
    ResponseEntity<Map> create(String repo) {
        return http.postForEntity("/api/development/repositories/" + repo + "/pr/reports", new HttpEntity<>(Map.of("number", 7, "headSha", HEAD, "baseSha", BASE), headers()), Map.class);
    }
    String register() {
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
    }
    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(), body; int status = 200;
                if (path.endsWith("/pulls/7")) {
                    int read = pullReads.incrementAndGet();
                    body = """
                        {"number":7,"title":"Update access","body":"Fixes #2; see other/repo#3","state":"open","draft":false,"changed_files":1,"additions":1,"deletions":1,
                         "base":{"sha":"%s","repo":{"full_name":"acme/service"}},"head":{"sha":"%s","repo":{"full_name":"acme/service"}}}
                        """.formatted(BASE, changed.get() && read > 1 ? "d".repeat(40) : HEAD);
                } else if (path.equals("/repos/acme/service/compare/" + BASE + "..." + HEAD)) {
                    String line = argumentSecret.get() ? "new ProcessBuilder(\\\"deploy\\\", \\\"--token\\\", \\\"fixture-pr-argument-secret\\\");" : "http.permitAll(); // token=ghp_" + "s".repeat(40);
                    String patch = missingPatch.get() ? "" : ",\"patch\":\"@@ -10,2 +10,2 @@\\n context;\\n-authenticated();\\n+" + line + "\"";
                    body = "{\"base_commit\":{\"sha\":\"" + BASE + "\"},\"merge_base_commit\":{\"sha\":\"" + MERGE_BASE + "\"},\"files\":[{\"filename\":\"src/main/java/Security.java\",\"status\":\"modified\",\"additions\":1,\"deletions\":1" + patch + "}]}";
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/check-runs")) {
                    status = deniedChecks.get() ? 403 : 200;
                    body = "{\"total_count\":1,\"check_runs\":[{\"id\":81,\"name\":\"Verify\",\"status\":\"completed\",\"conclusion\":\"success\",\"head_sha\":\"" + (wrongCheck.get() ? BASE : HEAD) + "\"}]}";
                } else if (path.equals("/repos/acme/service/commits/" + HEAD + "/status")) {
                    body = "{\"sha\":\"" + HEAD + "\",\"total_count\":0,\"statuses\":[]}";
                } else if (path.endsWith("/issues/2")) {
                    body = "{\"number\":2,\"title\":\"Access behavior\",\"state\":\"open\"}";
                } else if (path.equals("/repos/acme/service")) {
                    body = "{\"full_name\":\"acme/service\",\"default_branch\":\"main\",\"private\":false,\"archived\":false,\"open_issues_count\":0}";
                } else if (path.endsWith("/actions/runs")) body = "{\"workflow_runs\":[]}";
                else if (path.endsWith("/issues") || path.endsWith("/pulls")) body = "[]";
                else { status = 404; body = "{}"; }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            }); server.start(); return server;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @AfterAll static void stop() { server.stop(0); }
}
