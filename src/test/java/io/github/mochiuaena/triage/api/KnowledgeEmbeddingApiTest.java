package io.github.mochiuaena.triage.api;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;

import io.github.mochiuaena.triage.knowledge.*;
import io.github.mochiuaena.triage.material.MaterialService;
import io.github.mochiuaena.triage.source.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO",
    "spring.datasource.username=${TRIAGE_KNOWLEDGE_TEST_DB_USER:sa}",
    "spring.datasource.password=${TRIAGE_KNOWLEDGE_TEST_DB_PASSWORD:}",
    "spring.datasource.url=${TRIAGE_KNOWLEDGE_TEST_DB_URL:jdbc:h2:mem:knowledge-embedding;DB_CLOSE_DELAY=-1}",
    "triage.settings.key-file=target/knowledge-embedding-test-key"
})
class KnowledgeEmbeddingApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectRepository projects;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository store;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    static final Stub SERVICES = new Stub();
    Scope first;

    @BeforeAll static void startStub() { SERVICES.server.start(); }
    @AfterAll static void stopStub() { SERVICES.close(); }

    @BeforeEach void setup() throws Exception {
        SERVICES.reset();
        jdbc.update("DELETE FROM knowledge_chunk_embeddings");
        jdbc.update("DELETE FROM knowledge_embedding_jobs");
        jdbc.update("DELETE FROM search_model_providers");
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/knowledge-embedding-test-key"));
        var index = new SourceModels.Index("fixture", Instant.now(), 0, 0, 0, List.of());
        first = new Scope("PROJECT", projects.create("Embedding one", "embed-" + UUID.randomUUID(), directory.toString(), index).id());
    }

    UUID activate(Scope scope, UUID source, List<Document> documents) {
        var prior = source == null ? null : store.require(source);
        String root = prior == null ? directory + "/docs-" + UUID.randomUUID() : prior.root();
        var p = new Preview(UUID.randomUUID(), scope, "LOCAL", root, null, "1:" + directory,
            source == null ? UUID.randomUUID() : source, prior == null ? 0 : prior.revision(),
            List.of(), List.of(), 0, 0, 0, true, "manifest", Instant.now(), Instant.now().plusSeconds(600), limits());
        UUID job = UUID.randomUUID(); store.createJob(job, p); store.start(job); store.activate(job, p, documents);
        return p.sourceId();
    }

    Document doc(String path, String title, String text) {
        return new Document(path, MaterialService.sha(text), text.length(), title, text);
    }

    private <T> ResponseEntity<T> knowledgePost(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Knowledge", "1");
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> settingsPut(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Settings", "1");
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), type);
    }

    private Map<String, Object> embeddingInput() {
        return new LinkedHashMap<>(Map.of("displayName", "向量服务", "baseUrl", SERVICES.url(), "model",
            "test-embedding", "apiKey", "embedding-secret", "timeoutSeconds", 5, "version", 0));
    }

    private void saveEmbeddingConfig() {
        var response = settingsPut("/api/settings/search-models/embedding", embeddingInput(), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<Map> preview(Scope scope) {
        return knowledgePost("/api/knowledge/embeddings/preview",
            Map.of("scopeKind", scope.kind(), "scopeId", scope.id()), Map.class);
    }

    ResponseEntity<Map> startJob(Scope scope, Object previewId, boolean confirmed) {
        return knowledgePost("/api/knowledge/embeddings/jobs", Map.of("scopeKind", scope.kind(), "scopeId",
            scope.id(), "previewId", previewId, "documentsConfirmed", confirmed), Map.class);
    }

    Map terminalJob(Object jobId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var job = http.getForEntity("/api/knowledge/embeddings/jobs/" + jobId, Map.class).getBody();
            assertThat(job).isNotNull();
            assertThat((String) job.get("state")).isIn("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED");
        });
        return http.getForEntity("/api/knowledge/embeddings/jobs/" + jobId, Map.class).getBody();
    }

    @Test void indexRequiresConfiguredServicePreviewAndExplicitMaterialConsent() {
        activate(first, null, List.of(doc("guide.md", "Guide", "# 连接池\n诊断方法\n参数说明")));
        assertThat(preview(first).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        saveEmbeddingConfig();
        var saved = preview(first);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = saved.getBody();
        assertThat(body.get("chunks")).isEqualTo(1);
        assertThat(body.get("model")).isEqualTo("test-embedding");
        assertThat(body.get("note").toString()).contains("正文发送至").contains(SERVICES.url());
        assertThat(startJob(first, body.get("id"), false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var job = startJob(first, body.get("id"), true);
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        var finished = terminalJob(job.getBody().get("id"));
        assertThat(finished.get("state")).isEqualTo("SUCCEEDED");
        assertThat((int) finished.get("progress")).isEqualTo(1);
        assertThat((int) finished.get("usageCalls")).isEqualTo(1);
        assertThat(((Number) finished.get("usageTokens")).longValue()).isGreaterThan(0);
        assertThat(finished.get("message").toString()).contains("已知 tokens");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk_embeddings WHERE scope_id=?",
            Long.class, first.id().toString())).isEqualTo(1);
        assertThat(SERVICES.requests).isNotEmpty();
        assertThat(SERVICES.authorization).isEqualTo("Bearer embedding-secret");
        // Second preview after completion reuses the same fingerprint; no duplicate vectors.
        var again = startJob(first, preview(first).getBody().get("id"), true);
        finished = terminalJob(again.getBody().get("id"));
        assertThat(finished.get("state")).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk_embeddings WHERE scope_id=?",
            Long.class, first.id().toString())).isEqualTo(1);
    }

    @Test void embeddingFailureFailsTheJobAndKeepsPreviousVectors() {
        activate(first, null, List.of(doc("guide.md", "Guide", "稳定正文"), doc("extra.md", "Extra", "附加正文")));
        saveEmbeddingConfig();
        var job = startJob(first, preview(first).getBody().get("id"), true);
        assertThat(terminalJob(job.getBody().get("id")).get("state")).isEqualTo("SUCCEEDED");
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk_embeddings WHERE scope_id=?",
            Long.class, first.id().toString());
        SERVICES.embedStatus = 503;
        try {
            var failed = startJob(first, preview(first).getBody().get("id"), true);
            var finished = terminalJob(failed.getBody().get("id"));
            assertThat(finished.get("state")).isEqualTo("FAILED");
            assertThat(finished.get("message").toString()).contains("向量索引失败").doesNotContain("secret");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk_embeddings WHERE scope_id=?",
                Long.class, first.id().toString())).isEqualTo(before);
        } finally { SERVICES.embedStatus = 200; }
    }

    @Test void cancelStopsARunningJobBeforeItFinishes() {
        var documents = new ArrayList<Document>();
        for (int i = 0; i < 64; i++) documents.add(doc("doc" + i + ".md", "Guide", "cancel probe " + i + " " + "x".repeat(500)));
        activate(first, null, documents);
        saveEmbeddingConfig();
        SERVICES.delayMs = 120;
        try {
            var job = startJob(first, preview(first).getBody().get("id"), true);
            String jobId = job.getBody().get("id").toString();
            await().atMost(Duration.ofSeconds(5)).until(() -> {
                var current = http.getForEntity("/api/knowledge/embeddings/jobs/" + jobId, Map.class).getBody();
                return "RUNNING".equals(current.get("state"));
            });
            HttpHeaders headers = new HttpHeaders(); headers.set("X-Triage-Knowledge", "1");
            var cancelled = http.exchange("/api/knowledge/embeddings/jobs/" + jobId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
            assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
            var finished = terminalJob(jobId);
            assertThat(finished.get("state")).isIn("CANCELLED", "SUCCEEDED");
            if ("CANCELLED".equals(finished.get("state")))
                assertThat(finished.get("message").toString()).contains("取消");
        } finally { SERVICES.delayMs = 0; }
    }

    @Test void jobsAreScopedAndRequireTheKnowledgeMarker() {
        activate(first, null, List.of(doc("guide.md", "Guide", "正文")));
        assertThat(http.getForEntity("/api/knowledge/embeddings/jobs?scopeKind=" + first.kind() + "&scopeId=" + first.id(), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.exchange("/api/knowledge/embeddings/jobs?scopeKind=" + first.kind() + "&scopeId=" + first.id(),
            HttpMethod.POST, new HttpEntity<>(Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test void testEndpointReportsDimensionsTokensAndRerankAvailability() {
        assertThat(knowledgePost("/api/settings/search-models/embedding/test", null, Map.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Triage-Settings", "1");
        assertThat(http.exchange("/api/settings/search-models/embedding/test", HttpMethod.POST, new HttpEntity<>(headers), Map.class)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        saveEmbeddingConfig();
        var embedding = http.exchange("/api/settings/search-models/embedding/test", HttpMethod.POST,
            new HttpEntity<>(headers), Map.class).getBody();
        assertThat(embedding.get("success")).isEqualTo(true);
        assertThat((int) embedding.get("dimensions")).isEqualTo(4);
        assertThat(((Number) embedding.get("tokens")).longValue()).isGreaterThan(0);
        settingsPut("/api/settings/search-models/rerank",
            Map.of("displayName", "重排服务", "baseUrl", SERVICES.url(), "model", "test-rerank",
                "apiKey", "rerank-secret", "timeoutSeconds", 5, "version", 0), Map.class);
        var rerank = http.exchange("/api/settings/search-models/rerank/test", HttpMethod.POST,
            new HttpEntity<>(headers), Map.class).getBody();
        assertThat(rerank.get("success")).isEqualTo(true);
        SERVICES.embedStatus = 500;
        try {
            var failed = http.exchange("/api/settings/search-models/embedding/test", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class).getBody();
            assertThat(failed.get("success")).isEqualTo(false);
            assertThat(failed.get("message").toString()).contains("HTTP 500").doesNotContain("secret");
        } finally { SERVICES.embedStatus = 200; }
    }

    /** Local OpenAI-compatible embeddings stub with a deterministic text-to-vector function. */
    static final class Stub {
        final HttpServer server = create();
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final Map<String, float[]> vectors = new ConcurrentHashMap<>();
        volatile String authorization;
        volatile int embedStatus = 200;
        volatile int delayMs = 0;

        private static HttpServer create() {
            try { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }

        void reset() { requests.clear(); vectors.clear(); authorization = null; embedStatus = 200; delayMs = 0; }

        void register(String text, float[] vector) { vectors.put(text, vector); }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        Stub() {
            server.createContext("/embeddings", exchange -> {
                try (exchange) {
                    authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    requests.add(JSON.readValue(body, Map.class));
                    byte[] response;
                    if (embedStatus != 200) {
                        response = "{\"error\":\"stub failure\"}".getBytes(StandardCharsets.UTF_8);
                    } else {
                        if (delayMs > 0) Thread.sleep(delayMs);
                        JsonNode input = JSON.readTree(body).path("input");
                        var data = new ArrayList<Map<String, Object>>();
                        long tokens = 0;
                        for (int i = 0; i < input.size(); i++) {
                            String text = input.get(i).asText();
                            tokens += Math.max(1, text.length() / 4);
                            data.add(Map.of("object", "embedding", "index", i, "embedding", vector(text)));
                        }
                        response = JSON.writeValueAsBytes(Map.of("object", "list", "data", data, "model", "test-embedding",
                            "usage", Map.of("prompt_tokens", tokens, "total_tokens", tokens)));
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(embedStatus, response.length);
                    exchange.getResponseBody().write(response);
                } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            server.createContext("/rerank", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    JsonNode documents = JSON.readTree(body).path("documents");
                    var results = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < documents.size(); i++)
                        results.add(Map.of("index", i, "relevance_score", 1.0 / (i + 1)));
                    byte[] response = JSON.writeValueAsBytes(Map.of("results", results));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
        }

        float[] vector(String text) {
            float[] registered = vectors.get(text);
            if (registered != null) return registered;
            int h = MaterialService.sha(text).hashCode();
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) v[i] = ((h >> (i * 4)) & 0xF) / 15f * 2f - 1f;
            return v;
        }

        void close() { server.stop(0); }
    }
}
