package io.github.mochiuaena.triage.api;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:github-repositories;DB_CLOSE_DELAY=-1",
    "triage.github.token=fixture-github-token", "triage.github.refresh-seconds=0"
})
class GithubRepositoryApiTest {
    static final AtomicInteger calls = new AtomicInteger();
    static final AtomicBoolean unavailable = new AtomicBoolean();
    static final AtomicBoolean authenticated = new AtomicBoolean();
    static final AtomicBoolean actionsDenied = new AtomicBoolean();
    static final HttpServer github = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + github.getAddress().getPort());
    }

    @Test void repositoryReadsAreProtectedPersistedAndKeepThePreviousSnapshotAfterFailure() {
        var input = Map.of("repository", "https://github.com/acme/service");
        assertThat(http.postForEntity("/api/development/repositories", input, String.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(input, headers()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var registration = (Map<?, ?>) response.getBody().get("registration");
        String id = registration.get("id").toString();
        assertThat(authenticated).isTrue();
        assertThat(response.getBody().toString()).doesNotContain("fixture-github-token", "attacker.example");
        var health = (Map<?, ?>) response.getBody().get("health");
        var issues = (Map<?, ?>) health.get("issues");
        assertThat((java.util.List<?>) issues.get("items")).hasSize(1);
        var pullRequests = (Map<?, ?>) health.get("pullRequests");
        assertThat((java.util.List<?>) pullRequests.get("items")).hasSize(1);
        var runs = (Map<?, ?>) health.get("workflows");
        assertThat(runs.toString()).contains("failure", "Verify");
        int before = calls.get();
        assertThat(http.getForEntity("/api/development/repositories/" + id + "/health", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertThat(calls).hasValue(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM github_repositories", Long.class)).isEqualTo(1);
        assertThat(http.postForEntity("/api/development/repositories", new HttpEntity<>(input, headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
        var invalid = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "http://127.0.0.1/private"), headers()), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).contains("仓库");
        var crossOrigin = new HttpHeaders(); crossOrigin.set("Origin", "https://attacker.example");
        assertThat(http.exchange("/api/development/repositories", HttpMethod.GET, new HttpEntity<>(crossOrigin), String.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        unavailable.set(true);
        assertThat(http.postForEntity("/api/development/repositories/" + id + "/refresh", new HttpEntity<>(Map.of(), headers()), String.class).getStatusCode())
            .isEqualTo(HttpStatus.BAD_GATEWAY);
        var retained = http.getForObject("/api/development/repositories/" + id + "/health", Map.class);
        assertThat(retained).containsEntry("stale", true);
        assertThat(retained.get("health")).isEqualTo(health);
        assertThat(retained.get("error").toString()).isNotBlank();
        unavailable.set(false); actionsDenied.set(true);
        var partial = http.postForEntity("/api/development/repositories/" + id + "/refresh", new HttpEntity<>(Map.of(), headers()), Map.class);
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.OK);
        var partialHealth = (Map<?, ?>) partial.getBody().get("health");
        var missingActions = (Map<?, ?>) partialHealth.get("workflows");
        assertThat(missingActions.get("available")).isEqualTo(false);
        assertThat(missingActions.get("message").toString()).contains("权限");
        assertThat(http.exchange("/api/development/repositories/" + id + "?confirmId=" + java.util.UUID.randomUUID(),
            HttpMethod.DELETE, new HttpEntity<>(headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange("/api/development/repositories/" + id + "?confirmId=" + id,
            HttpMethod.DELETE, new HttpEntity<>(headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    static HttpHeaders headers() {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Triage-Development", "1"); return headers;
    }
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                calls.incrementAndGet();
                authenticated.set("Bearer fixture-github-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
                String path = exchange.getRequestURI().getPath();
                String body = switch (path) {
                    case "/repos/acme/service" -> "{\"full_name\":\"acme/service\",\"description\":\"Example\",\"default_branch\":\"main\",\"private\":false,\"archived\":false,\"open_issues_count\":2}";
                    case "/repos/acme/service/issues" -> "[{\"number\":7,\"title\":\"Slow request\",\"updated_at\":\"2026-10-13T00:00:00Z\",\"labels\":[]},{\"number\":8,\"title\":\"PR\",\"pull_request\":{}}]";
                    case "/repos/acme/service/pulls" -> "[{\"number\":8,\"title\":\"Fix\",\"draft\":true,\"updated_at\":\"2026-10-13T00:00:00Z\",\"head\":{\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}]";
                    case "/repos/acme/service/actions/runs" -> "{\"workflow_runs\":[{\"id\":9,\"name\":\"Verify\",\"status\":\"completed\",\"conclusion\":\"failure\",\"head_branch\":\"main\",\"head_sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"updated_at\":\"2026-10-13T00:00:00Z\",\"html_url\":\"https://attacker.example\"}]}";
                    default -> "{}";
                };
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(unavailable.get() ? 503 : actionsDenied.get() && path.endsWith("/actions/runs") ? 403 : 200, bytes.length);
                try (var out = exchange.getResponseBody()) { out.write(bytes); }
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @AfterAll static void stop() { github.stop(0); }
}
