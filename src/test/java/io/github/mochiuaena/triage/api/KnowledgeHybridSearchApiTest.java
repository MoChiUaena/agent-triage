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
    "spring.datasource.url=${TRIAGE_KNOWLEDGE_TEST_DB_URL:jdbc:h2:mem:knowledge-hybrid;DB_CLOSE_DELAY=-1}",
    "triage.settings.key-file=target/knowledge-hybrid-test-key"
})
class KnowledgeHybridSearchApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectRepository projects;
    @Autowired KnowledgeRepository store;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    static final Stub SERVICES = new Stub();
    Scope first, second;

    static final String QUERY = "连接池 配置";
    static final String TEXT_A = "连接池配置参数说明与诊断方法";
    static final String TEXT_B = "数据库连接池的正确配置方法与常见问题排查";
    static final String TEXT_C = "frontend deployment pipeline checklist";
    static final String TEXT_D = "配置清单模板与示例";

    @BeforeAll static void startStub() { SERVICES.server.start(); }
    @AfterAll static void stopStub() { SERVICES.close(); }

    @BeforeEach void setup() throws Exception {
        SERVICES.reset();
        jdbc.update("DELETE FROM knowledge_chunk_embeddings");
        jdbc.update("DELETE FROM knowledge_embedding_jobs");
        jdbc.update("DELETE FROM search_model_providers");
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("target/knowledge-hybrid-test-key"));
        var index = new SourceModels.Index("fixture", Instant.now(), 0, 0, 0, List.of());
        first = new Scope("PROJECT", projects.create("Hybrid one", "hybrid-" + UUID.randomUUID(), directory.toString(), index).id());
        second = new Scope("PROJECT", projects.create("Hybrid two", "hybrid-2-" + UUID.randomUUID(), directory.toString(), index).id());
        SERVICES.register(QUERY, new float[] {1f, 0f, 0f, 0f});
        SERVICES.register(TEXT_A, new float[] {0.5f, 0.8f, 0f, 0f});
        SERVICES.register(TEXT_B, new float[] {0.99f, 0.05f, 0f, 0f});
        SERVICES.register(TEXT_C, new float[] {0.8f, 0.6f, 0f, 0f});
        SERVICES.register(TEXT_D, new float[] {0f, 1f, 0f, 0f});
        activate(first, null, List.of(doc("a.md", "连接池指南", TEXT_A), doc("b.md", "数据库文档", TEXT_B),
            doc("c.md", "发布流程", TEXT_C), doc("d.md", "模板", TEXT_D)));
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

    private <T> ResponseEntity<T> post(String path, Object body, String marker, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set(marker, "1");
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    private <T> ResponseEntity<T> put(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Settings", "1");
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), type);
    }

    private ResponseEntity<Map> hybrid(Scope scope, String extra) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("scopeKind", scope.kind(), "scopeId", scope.id(),
            "query", QUERY, "confirmed", true));
        if (extra.contains("rerank=true")) body.put("rerank", true);
        if (extra.contains("documentsConfirmed=true")) body.put("documentsConfirmed", true);
        if (extra.contains("confirmed=false")) body.put("confirmed", false);
        if (extra.contains("limit=2")) body.put("limit", 2);
        if (extra.contains("docType=TEXT")) body.put("docType", "TEXT");
        return post("/api/knowledge/hybrid-search", body, "X-Triage-Knowledge", Map.class);
    }

    @SuppressWarnings("unchecked")
    private void saveConfig(String kind, String model) {
        var state = http.getForEntity("/api/settings/search-models", Map.class).getBody();
        var existing = (Map<String, Object>) state.get(kind);
        long version = existing == null ? 0 : ((Number) existing.get("version")).longValue();
        var body = new LinkedHashMap<String, Object>();
        body.put("displayName", kind.equals("rerank") ? "重排服务" : "向量服务");
        body.put("baseUrl", SERVICES.url());
        body.put("model", model);
        body.put("apiKey", "search-secret");
        body.put("timeoutSeconds", 5);
        body.put("version", version);
        var response = put("/api/settings/search-models/" + kind, body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void buildIndex(Scope scope) {
        var preview = post("/api/knowledge/embeddings/preview", Map.of("scopeKind", scope.kind(), "scopeId", scope.id()),
            "X-Triage-Knowledge", Map.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var job = post("/api/knowledge/embeddings/jobs", Map.of("scopeKind", scope.kind(), "scopeId", scope.id(),
            "previewId", preview.getBody().get("id"), "documentsConfirmed", true), "X-Triage-Knowledge", Map.class);
        assertThat(job.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var current = http.getForEntity("/api/knowledge/embeddings/jobs/" + job.getBody().get("id"), Map.class).getBody();
            assertThat((String) current.get("state")).isEqualTo("SUCCEEDED");
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map> hits(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) response.getBody().get("results");
    }

    private List<String> paths(ResponseEntity<Map> response) {
        return hits(response).stream().map(h -> ((Map) h.get("citation")).get("path").toString()).toList();
    }

    @Test void hybridFusesKeywordAndVectorHitsWithOriginsAndUsage() {
        saveConfig("embedding", "test-embedding");
        buildIndex(first);
        var response = hybrid(first, "");
        assertThat(paths(response)).containsExactly("b.md", "a.md", "c.md", "d.md");
        var results = hits(response);
        assertThat(results.get(0).get("matchedBy").toString()).isEqualTo("[KEYWORD, VECTOR]");
        assertThat(response.getBody().get("recallMethod")).isEqualTo("HYBRID");
        assertThat(((Map) results.get(0).get("citation")).get("recallMethod")).isEqualTo("FUSED");
        assertThat(((Map) results.get(2).get("citation")).get("recallMethod")).isEqualTo("VECTOR");
        assertThat(results.get(2).get("matchedBy").toString()).isEqualTo("[VECTOR]");
        assertThat(results.get(1).get("fusedScore").toString()).isNotEqualTo(results.get(0).get("fusedScore").toString());
        var vector = (Map) response.getBody().get("vector");
        assertThat(vector.get("status")).isEqualTo("USED");
        assertThat((int) vector.get("dimension")).isEqualTo(4);
        assertThat(((Number) vector.get("queryTokens")).longValue()).isGreaterThan(0);
        assertThat(((Number) vector.get("indexedChunks")).longValue()).isEqualTo(4L);
        assertThat(response.getBody().get("coverage").toString()).contains("RRF").contains("已使用");
        var rerank = (Map) response.getBody().get("rerank");
        assertThat(rerank.get("status")).isEqualTo("OFF");
        assertThat(rerank.get("reason").toString()).contains("未启用重排");
        // The keyword-only endpoint is untouched by the hybrid path.
        var keyword = http.getForEntity("/api/knowledge/search?scopeKind=" + first.kind() + "&scopeId=" + first.id()
            + "&query={q}", Map.class, QUERY).getBody();
        assertThat(keyword.get("recallMethod")).isEqualTo("KEYWORD");
    }

    @Test void rerankReordersFusedCandidatesAndFallsBackOnFailureWithReason() {
        saveConfig("embedding", "test-embedding");
        saveConfig("rerank", "test-rerank");
        buildIndex(first);
        SERVICES.rerankResult = List.of(new double[] {3, 0.95}, new double[] {2, 0.9}, new double[] {1, 0.5}, new double[] {0, 0.1});
        var response = hybrid(first, "&rerank=true&documentsConfirmed=true");
        assertThat(paths(response)).containsExactly("d.md", "c.md", "a.md", "b.md");
        var results = hits(response);
        assertThat(((Number) results.get(0).get("rerankScore")).doubleValue()).isEqualTo(0.95);
        assertThat(((Map) results.get(0).get("citation")).get("recallMethod")).isEqualTo("VECTOR");
        var rerank = (Map) response.getBody().get("rerank");
        assertThat(rerank.get("status")).isEqualTo("USED");
        assertThat((int) rerank.get("candidates")).isEqualTo(4);
        // Rerank failure keeps the fused order and explains the fallback.
        SERVICES.rerankStatus = 500;
        try {
            var failed = hybrid(first, "&rerank=true&documentsConfirmed=true");
            assertThat(paths(failed)).containsExactly("b.md", "a.md", "c.md", "d.md");
            var status = (Map) failed.getBody().get("rerank");
            assertThat(status.get("status")).isEqualTo("FAILED");
            assertThat(status.get("reason").toString()).contains("HTTP 500");
        } finally { SERVICES.rerankStatus = 200; SERVICES.rerankResult = List.of(); }
        // Reorder requests without material consent are rejected before any excerpt leaves.
        assertThat(hybrid(first, "&rerank=true").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void vectorUnavailabilityAndFailuresFallBackToKeywordWithExplanation() {
        saveConfig("embedding", "test-embedding");
        // No index yet: keyword-only result, and the query text never leaves the machine.
        var response = hybrid(first, "");
        assertThat(paths(response)).containsExactly("a.md", "b.md");
        assertThat(hits(response).stream().allMatch(h -> h.get("matchedBy").toString().equals("[KEYWORD]"))).isTrue();
        var vector = (Map) response.getBody().get("vector");
        assertThat(vector.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(vector.get("reason").toString()).contains("尚未建立向量索引");
        assertThat(SERVICES.requests).isEmpty();

        buildIndex(first);
        // A changed embedding model invalidates the fingerprint until the index is rebuilt.
        saveConfig("embedding", "test-embedding-2");
        var stale = (Map) hybrid(first, "").getBody().get("vector");
        assertThat(stale.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(stale.get("reason").toString()).contains("重建");
        saveConfig("embedding", "test-embedding");

        // Embedding outage during search degrades to keyword results with the failure reason.
        SERVICES.embedStatus = 503;
        try {
            var failed = (Map) hybrid(first, "").getBody();
            assertThat(((Map) failed.get("vector")).get("status")).isEqualTo("FAILED");
            assertThat(((Map) failed.get("vector")).get("reason").toString()).contains("HTTP 503");
            assertThat(((List<Map<String, Object>>) failed.get("results")).stream()
                .map(h -> ((Map<String, Object>) h.get("citation")).get("path")))
                .containsExactly("a.md", "b.md");
        } finally { SERVICES.embedStatus = 200; }

        // Vector candidates never cross scopes: the second scope has its own index only.
        activate(second, null, List.of(doc("e.md", "另一项目", TEXT_B)));
        buildIndex(second);
        var scoped = hybrid(second, "");
        assertThat(((Map) scoped.getBody().get("vector")).get("status")).isEqualTo("USED");
        assertThat(paths(hybrid(first, ""))).doesNotContain("e.md");
    }

    @Test void consentGatesLimitsAndDocTypeFilterAreEnforced() {
        assertThat(hybrid(first, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        saveConfig("embedding", "test-embedding");
        assertThat(hybrid(first, "&confirmed=false").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        saveConfig("rerank", "test-rerank");
        buildIndex(first);
        assertThat(hybrid(first, "&rerank=true").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> badLimit = new LinkedHashMap<>(Map.of("scopeKind", first.kind(), "scopeId", first.id(),
            "query", QUERY, "confirmed", true, "limit", 0));
        assertThat(post("/api/knowledge/hybrid-search", badLimit, "X-Triage-Knowledge", Map.class).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        // TEXT filter has no indexed chunks of that type, so the vector stage explains and degrades.
        var filtered = hybrid(first, "&docType=TEXT");
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hits(filtered)).isEmpty();
        assertThat(((Map) filtered.getBody().get("vector")).get("reason").toString()).contains("筛选类型");
        // POST without the knowledge marker is rejected by the same access rules as K2.
        HttpHeaders cross = new HttpHeaders(); cross.setOrigin("https://evil.example");
        assertThat(http.exchange("/api/knowledge/hybrid-search", HttpMethod.POST,
            new HttpEntity<>(Map.of("scopeKind", "PROJECT", "scopeId", first.id().toString(), "query", "x"), cross),
            Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Local OpenAI-compatible embeddings and rerank stub with registered vectors. */
    static final class Stub {
        final HttpServer server = create();
        final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
        final Map<String, float[]> vectors = new ConcurrentHashMap<>();
        volatile List<double[]> rerankResult = List.of();
        volatile String authorization;
        volatile int embedStatus = 200;
        volatile int rerankStatus = 200;

        private static HttpServer create() {
            try { return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); }
            catch (IOException e) { throw new IllegalStateException(e); }
        }

        void reset() {
            requests.clear(); vectors.clear(); rerankResult = List.of(); authorization = null;
            embedStatus = 200; rerankStatus = 200;
        }

        void register(String text, float[] vector) { vectors.put(text, vector); }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        Stub() {
            server.createContext("/embeddings", exchange -> {
                try (exchange) {
                    authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    requests.add(JSON.readValue(body, Map.class));
                    byte[] response;
                    if (embedStatus != 200) response = "{\"error\":\"stub failure\"}".getBytes(StandardCharsets.UTF_8);
                    else {
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
                }
            });
            server.createContext("/rerank", exchange -> {
                try (exchange) {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    JsonNode documents = JSON.readTree(body).path("documents");
                    byte[] response;
                    if (rerankStatus != 200) response = "{\"error\":\"stub failure\"}".getBytes(StandardCharsets.UTF_8);
                    else {
                        var results = new ArrayList<Map<String, Object>>();
                        if (rerankResult.isEmpty())
                            for (int i = 0; i < documents.size(); i++)
                                results.add(Map.of("index", i, "relevance_score", 1.0 / (i + 1)));
                        else
                            for (double[] entry : rerankResult)
                                results.add(Map.of("index", (int) entry[0], "relevance_score", entry[1]));
                        response = JSON.writeValueAsBytes(Map.of("results", results));
                    }
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(rerankStatus, response.length);
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
