package io.github.mochiuaena.triage.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:knowledge-github;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/knowledge-github-key"
        })
class KnowledgeGithubApiTest {
    static HttpServer fixture;
    static final String SHA = "d".repeat(40), TREE = "e".repeat(40);
    static final List<String> requested = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void fixture(DynamicPropertyRegistry properties) throws Exception {
        var json = new ObjectMapper();
        byte[] text =
                "# Public Java fixture\nArchitecture manual\n".getBytes(StandardCharsets.UTF_8);
        var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + text.length + "\0").getBytes(StandardCharsets.UTF_8));
        String blob = HexFormat.of().formatHex(digest.digest(text));
        fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext(
                "/",
                x -> {
                    String path = x.getRequestURI().toString();
                    requested.add(path);
                    Object result;
                    if (path.endsWith("/git/commits/" + SHA))
                        result = Map.of("sha", SHA, "tree", Map.of("sha", TREE));
                    else if (path.endsWith("/git/trees/" + TREE + "?recursive=1"))
                        result =
                                Map.of(
                                        "sha",
                                        TREE,
                                        "truncated",
                                        false,
                                        "tree",
                                        List.of(
                                                Map.of(
                                                        "path",
                                                        "README.md",
                                                        "mode",
                                                        "100644",
                                                        "type",
                                                        "blob",
                                                        "size",
                                                        text.length,
                                                        "sha",
                                                        blob),
                                                Map.of(
                                                        "path",
                                                        "docs/guide.txt",
                                                        "mode",
                                                        "100644",
                                                        "type",
                                                        "blob",
                                                        "size",
                                                        text.length,
                                                        "sha",
                                                        blob)));
                    else if (path.endsWith("/git/blobs/" + blob))
                        result =
                                Map.of(
                                        "sha",
                                        blob,
                                        "size",
                                        text.length,
                                        "encoding",
                                        "base64",
                                        "content",
                                        Base64.getEncoder().encodeToString(text));
                    else {
                        x.sendResponseHeaders(404, -1);
                        x.close();
                        return;
                    }
                    byte[] bytes = json.writeValueAsBytes(result);
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        fixture.start();
        properties.add(
                "triage.github.api-base-url",
                () -> "http://127.0.0.1:" + fixture.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        fixture.stop(0);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    Map post(String path, Object body, HttpStatus status) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Triage-Knowledge", "1");
        var response =
                http.postForEntity("/api/knowledge" + path, new HttpEntity<>(body, h), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(status);
        return response.getBody();
    }

    @Test
    void registeredFixedShaImportsIdempotentlyAndHistoricalRepositoryScopeRemainsReadable()
            throws Exception {
        UUID repo = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?)",
                repo.toString(),
                "fixture",
                "java-" + repo,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "{}");
        String source = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map preview =
                    post(
                            "/previews",
                            Map.of("scopeKind", "REPOSITORY", "scopeId", repo, "commitSha", SHA),
                            HttpStatus.CREATED);
            assertThat((List) preview.get("files")).hasSize(2);
            source = preview.get("sourceId").toString();
            Map job = post("/imports", Map.of("previewId", preview.get("id")), HttpStatus.ACCEPTED);
            for (int i = 0;
                    i < 200 && List.of("QUEUED", "RUNNING").contains(job.get("state"));
                    i++) {
                Thread.sleep(25);
                job = http.getForObject("/api/knowledge/jobs/" + job.get("id"), Map.class);
            }
            assertThat(job.get("state")).isEqualTo("SUCCEEDED");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_versions WHERE source_id=?",
                                Integer.class,
                                source))
                .isEqualTo(2);
        String version =
                jdbc.queryForList(
                                "SELECT id FROM knowledge_versions WHERE source_id=?",
                                String.class,
                                source)
                        .getFirst();
        jdbc.update("DELETE FROM github_repositories WHERE id=?", repo.toString());
        var original =
                http.getForObject(
                        "/api/knowledge/versions/"
                                + version
                                + "?scopeKind=REPOSITORY&scopeId="
                                + repo,
                        Map.class);
        assertThat(original.get("commitSha")).isEqualTo(SHA);
        assertThat(original.get("text").toString()).contains("Architecture manual");
        assertThat(requested).noneMatch(p -> p.contains("/contents/") || p.contains("main"));
    }
}
