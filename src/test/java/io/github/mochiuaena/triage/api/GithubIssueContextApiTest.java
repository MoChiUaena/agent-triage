package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:issue-context;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key", "triage.mode=MODEL", "triage.model.api-key=fixture-model-key",
    "triage.model.name=fixture-issue", "triage.model.timeout=5s"
})
class GithubIssueContextApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String HEAD = "a".repeat(40);
    static final AtomicBoolean commentsChanged = new AtomicBoolean(), manyComments = new AtomicBoolean(), invalidOwner = new AtomicBoolean(), citeSymbol = new AtomicBoolean();
    static final AtomicInteger modelCalls = new AtomicInteger();
    static final AtomicReference<String> modelInput = new AtomicReference<>();
    static final java.util.concurrent.ExecutorService serverWorkers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon(true).name("issue-context-fixture-", 0).factory());
    static final HttpServer server = start();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    String repo, project;
    long revision;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("triage.github.api-base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        properties.add("triage.model.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/model");
    }

    @AfterAll static void stop() { server.stop(0); serverWorkers.shutdownNow(); }

    @BeforeEach void reset() throws Exception {
        commentsChanged.set(false); manyComments.set(false); invalidOwner.set(false); citeSymbol.set(false);
        modelCalls.set(0); modelInput.set(null);
        jdbc.update("DELETE FROM issue_reports");
        jdbc.update("DELETE FROM source_projects");
        jdbc.update("DELETE FROM github_repositories");
        Files.createDirectories(directory.resolve("src/main/java"));
        Files.writeString(directory.resolve("src/main/java/OrderService.java"), """
            package com.example;
            class OrderService {
                void submit(boolean strict) { }
            }
            """.stripTrailing() + "\n");
        var response = http.postForEntity("/api/development/repositories", new HttpEntity<>(Map.of("repository", "acme/service"), headers("X-Triage-Development")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        repo = ((Map<?, ?>) response.getBody().get("registration")).get("id").toString();
        var source = http.postForEntity("/api/source-projects", new HttpEntity<>(Map.of("name", "订单本机项目",
            "service", "order-service", "directory", directory.toString()), headers("X-Triage-Source")), Map.class);
        assertThat(source.getStatusCode()).isEqualTo(HttpStatus.OK);
        project = source.getBody().get("id").toString();
        revision = ((Number) source.getBody().get("revision")).longValue();
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers("X-Triage-Development")), type);
    }

    private ResponseEntity<Map> preview(Map extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("number", 2));
        body.putAll(extra);
        return post("/api/development/repositories/" + repo + "/issue/previews", body, Map.class);
    }

    private ResponseEntity<Map> create(Map preview, boolean model) {
        return post("/api/development/repositories/" + repo + "/issue/reports",
            Map.of("previewId", preview.get("id"), "useModel", model), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map> evidence(Map preview, String kind) {
        return (List<Map>) ((List<?>) preview.get("evidence")).stream()
            .filter(item -> ((Map<?, ?>) item).get("kind").equals(kind)).toList();
    }

    private List<String> gapTexts(Map preview) {
        return ((List<?>) preview.get("gaps")).stream().map(Object::toString).toList();
    }

    @Test void commentsAreBoundedFingerprintedAndExtendPathCandidates() {
        Map body = preview(Map.of()).getBody();
        List<Map> comments = evidence(body, "COMMENT");
        assertThat(comments).hasSize(3);
        assertThat(comments.getFirst().get("id").toString()).isEqualTo("COMMENT-1");
        assertThat(comments.getFirst().get("text").toString()).contains("carol").doesNotContain("x".repeat(2001));
        assertThat(comments.get(1).get("text").toString()).contains("OrderService");
        assertThat(comments.getFirst().get("url").toString()).startsWith("https://github.com/acme/service/issues/2");
        // 评论中提到的路径进入候选并读取为代码证据；正文路径仍优先。
        assertThat(evidence(body, "CODE").toString()).contains("Example.java").contains("Guard.java");
        assertThat(gapTexts(body).stream().anyMatch(text -> text.contains("不构成因果") || text.contains("不构成"))).isTrue();
        List<Map> commits = evidence(body, "COMMIT");
        assertThat(commits.getFirst().get("title").toString()).contains("未证明与 Issue 有关");

        var created = create(body, true);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(modelCalls).hasValue(1);
        assertThat(modelInput.get()).contains("COMMENT-1").doesNotContain("fixture-model-key", "fixture-github-key");
        assertThat(((Map) created.getBody().get("model")).get("state")).isEqualTo("SUCCEEDED");

        // 评论变化后旧预览失效：必须重新预览。
        commentsChanged.set(true);
        assertThat(create(body, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        commentsChanged.set(false);
        Map again = preview(Map.of()).getBody();
        assertThat(create(again, true).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test void commentOverflowIsTruncatedWithExplicitCoverageNote() {
        manyComments.set(true);
        Map body = preview(Map.of()).getBody();

        assertThat(evidence(body, "COMMENT")).hasSize(5);
        assertThat(gapTexts(body).stream().anyMatch(text -> text.contains("只读取最近 5 条评论"))).isTrue();
    }

    @Test void symbolSearchAddsStaticCandidatesWithExplicitRevisionBoundaries() {
        Map without = preview(Map.of()).getBody();
        assertThat(evidence(without, "SYMBOL")).isEmpty();

        Map body = preview(Map.of("sourceProjectId", project, "sourceRevision", revision)).getBody();
        List<Map> symbols = evidence(body, "SYMBOL");
        assertThat(symbols).isNotEmpty();
        assertThat(symbols.getFirst().get("file").toString()).isEqualTo("src/main/java/OrderService.java");
        assertThat(symbols.getFirst().get("title").toString()).contains("静态候选").contains("版本未");
        assertThat(gapTexts(body).stream().anyMatch(text -> text.contains("静态") && text.contains("不证明执行路径"))).isTrue();

        Map again = preview(Map.of("sourceProjectId", project, "sourceRevision", revision)).getBody();
        citeSymbol.set(true);
        var created = create(again, true);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Map) created.getBody().get("model")).get("state")).isEqualTo("SUCCEEDED");
        assertThat(modelInput.get()).contains("SYMBOL-").contains("OrderService.java");

        assertThat(preview(Map.of("sourceProjectId", project, "sourceRevision", 99L)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(preview(Map.of("sourceProjectId", UUID.randomUUID().toString(), "sourceRevision", 1L)).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test void codeownerScopeAndCausationBoundariesAreEnforced() {
        Map body = preview(Map.of()).getBody();
        List<Map> owners = (List<Map>) body.get("owners");
        assertThat(owners.toString()).contains("alice").contains("carol");
        assertThat(owners.toString()).doesNotContain("dave").doesNotContain("org/team").doesNotContain("someone");
        List<Map> codeownerEvidence = evidence(body, "CODEOWNER");
        assertThat(codeownerEvidence).isNotEmpty();
        assertThat(codeownerEvidence.getFirst().get("text").toString()).contains("规则").contains("src/main/java/");
        assertThat(codeownerEvidence.getFirst().get("title").toString()).contains("不代表已同意");

        // 评论提及的账号（someone）与提交不是负责人或因果依据；模型只能在 owners 范围内提名。
        Map again = preview(Map.of()).getBody();
        invalidOwner.set(true);
        var invalid = create(again, true);
        assertThat(((Map) invalid.getBody().get("model")).get("state")).isEqualTo("INVALID_OUTPUT");
        assertThat(invalid.getBody().get("source")).isEqualTo("LOCAL_RULES");
        assertThat(modelCalls).hasValue(1);
    }

    static HttpHeaders headers(String marker) { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set(marker, "1"); return h; }

    static String comment(long id, String login, String url, String body) {
        return """
            {"id":%d,"user":{"login":"%s"},"html_url":"%s","created_at":"2026-10-24T00:0%d:00Z","body":"%s"}""".formatted(id, login, url, (int) id, body.replace("\"", "\\\""));
    }

    static String commentsJson() {
        if (commentsChanged.get()) return JSON_COMMENT_CHANGED;
        String longBody = "x".repeat(2400);
        String tail = manyComments.get()
            ? "," + comment(4, "alice", "https://github.com/acme/service/issues/2#issuecomment-4", "another") + ","
                + comment(5, "dave", "https://github.com/acme/service/issues/2#issuecomment-6", "late")
            : "";
        return "[" + comment(1, "carol", "https://github.com/acme/service/issues/2#issuecomment-1",
                "查看 src/main/java/Guard.java:7；@someone 也提到了。") + ","
            + comment(2, "bob", "https://github.com/acme/service/issues/2#issuecomment-2",
                "OrderService.submit 看起来相关。") + ","
            + comment(3, "alice", "https://github.com/acme/service/issues/2#issuecomment-3", longBody) + tail + "]";
    }

    static final String JSON_COMMENT_CHANGED = "[" + comment(1, "carol", "https://github.com/acme/service/issues/2#issuecomment-1", "changed content") + "]";

    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverWorkers);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; Object data;
                if (path.endsWith("/chat/completions")) {
                    modelCalls.incrementAndGet(); modelInput.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    var content = Map.of("summary", "评论指出 Guard 与 OrderService 相关；复现仍未验证。", "category", "BUG", "priority", "P2",
                        "reason", "评论与正文提及相关文件", "evidenceIds", List.of(citeSymbol.get() ? "SYMBOL-1" : "COMMENT-1"),
                        "questions", List.of("哪个版本失败？"),
                        "actions", List.of(Map.of("text", "复现评论中描述的路径", "evidenceIds", List.of("COMMENT-1"))),
                        "ownerCandidates", List.of(Map.of("login", invalidOwner.get() ? "outsider" : "alice", "reason", "贡献者候选", "evidenceIds", List.of("OWNER-1"))));
                    data = Map.of("id", "fixture", "object", "chat.completion", "created", 1, "model", "fixture-issue", "choices",
                        List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", JSON.writeValueAsString(content)))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 50, "total_tokens", 150));
                } else if (path.endsWith("/pulls/2")) {
                    status = 404; data = Map.of();
                } else if (path.endsWith("/issues/2")) {
                    data = Map.of("number", 2, "title", "Failing request", "body", "Request fails in src/main/java/Example.java:3\nRefer to OrderService",
                        "state", "open", "updated_at", "2026-10-24T00:00:00Z", "labels", List.of(Map.of("name", "bug")),
                        "assignees", List.of(), "comments", manyComments.get() ? 8 : 3);
                } else if (path.endsWith("/issues/2/comments")) {
                    data = JSON.readTree(commentsJson());
                } else if (path.equals("/repos/acme/service")) {
                    data = Map.of("full_name", "acme/service", "default_branch", "main", "private", false, "archived", false, "open_issues_count", 1);
                } else if (path.endsWith("/commits/main")) {
                    data = Map.of("sha", HEAD);
                } else if (path.endsWith("/commits")) {
                    data = List.of(Map.of("sha", HEAD, "commit", Map.of("message", "Update Example", "committer", Map.of("date", "2026-10-24T00:00:00Z"))));
                } else if (path.endsWith("/contributors")) {
                    data = List.of(Map.of("login", "alice", "contributions", 4));
                } else if (path.endsWith("/contents/.github/CODEOWNERS")) {
                    data = Map.of("type", "file", "path", ".github/CODEOWNERS", "encoding", "base64", "size", 64,
                        "content", Base64.getEncoder().encodeToString(("* @alice\nsrc/main/java/ @carol @org/team\ndocs/ @dave\n").getBytes(StandardCharsets.UTF_8)));
                } else if (path.endsWith("/contents/src/main/java/Example.java") || path.endsWith("/contents/src/main/java/Guard.java")) {
                    String source = "package acme;\nclass Handler {\n    void run() {\n        client.call();\n    }\n\n    void guard() {\n        security();\n    }\n\n    void security() {\n    }\n}";
                    data = Map.of("type", "file", "path", path.endsWith("Example.java") ? "src/main/java/Example.java" : "src/main/java/Guard.java",
                        "encoding", "base64", "size", source.length(), "content", Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)));
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
