package io.github.mochiuaena.triage.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.settings.ProviderRegistry;
import io.github.mochiuaena.triage.store.RunRepository;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:conversations;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/conversation-test-key"
        })
class ConversationApiTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("TRIAGE_CONVERSATION_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add(
                    "spring.datasource.username",
                    () -> System.getenv("TRIAGE_CONVERSATION_TEST_DB_USER"));
            r.add(
                    "spring.datasource.password",
                    () -> System.getenv("TRIAGE_CONVERSATION_TEST_DB_PASSWORD"));
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired io.github.mochiuaena.triage.knowledge.KnowledgeRepository knowledgeStore;
    @Autowired RunRepository runs;
    @Autowired ProviderRegistry providers;
    UUID repository;

    @BeforeEach
    void fixture() {
        providers.select("DEMO", null);
        repository = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?)",
                repository.toString(),
                "fixture",
                "repo-" + repository,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "{}");
    }

    JsonNode post(String route, Object input, int expected) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Triage-Conversation", "1");
        var result =
                http.postForEntity(
                        "/api/conversations" + route, new HttpEntity<>(input, h), JsonNode.class);
        assertThat(result.getStatusCode().value()).as(result.toString()).isEqualTo(expected);
        return result.getBody();
    }

    UUID service(String question) {
        var r =
                new Run(
                        UUID.randomUUID(),
                        question,
                        "order-service",
                        5,
                        Scenario.NORMAL,
                        "DEMO",
                        true,
                        Status.SUCCEEDED,
                        Instant.now(),
                        Instant.now(),
                        0,
                        List.of(),
                        List.of(),
                        null,
                        null);
        runs.insert(r);
        return r.id();
    }

    UUID saved(String type) throws Exception {
        if (type.equals("SERVICE")) return service("original question");
        UUID id = UUID.randomUUID();
        var p = new HashMap<String, Object>();
        p.put("id", id);
        p.put("repositoryId", repository);
        p.put("repository", "fixture/repo");
        p.put("createdAt", Instant.now());
        if (type.equals("WORKFLOW")) {
            p.put("state", "COMPLETED");
            p.put("updatedAt", Instant.now());
            p.put("tasks", List.of());
        }
        if (type.equals("ISSUE")) {
            p.put(
                    "evidence",
                    List.of(Map.of("id", "e1", "kind", "SOURCE", "text", "PRIVATE_CODE")));
            p.put(
                    "proposal",
                    Map.of(
                            "summary",
                            "safe summary",
                            "actions",
                            List.of(Map.of("text", "safe action", "evidenceIds", List.of("e1")))));
        }
        String table =
                Map.of(
                                "PR",
                                "pr_reports",
                                "ISSUE",
                                "issue_reports",
                                "CI",
                                "ci_reports",
                                "WORKFLOW",
                                "development_workflows")
                        .get(type);
        if (type.equals("WORKFLOW"))
            jdbc.update(
                    "INSERT INTO "
                            + table
                            + "(id,repository_id,created_at,state,payload) VALUES(?,?,?,?,?)",
                    id.toString(),
                    repository.toString(),
                    OffsetDateTime.now(),
                    "COMPLETED",
                    json.writeValueAsString(p));
        else
            jdbc.update(
                    "INSERT INTO "
                            + table
                            + "(id,repository_id,created_at,payload) VALUES(?,?,?,?)",
                    id.toString(),
                    repository.toString(),
                    OffsetDateTime.now(),
                    json.writeValueAsString(p));
        return id;
    }

    JsonNode create(String type, UUID id) {
        return post("", Map.of("origin", Map.of("type", type, "reportId", id)), 201);
    }

    @Test
    void createsAllFiveOriginsAndRetainsImmutableSnapshotAfterOriginalRemoval() throws Exception {
        for (String type : List.of("SERVICE", "PR", "ISSUE", "CI", "WORKFLOW")) {
            UUID report = saved(type);
            JsonNode c = create(type, report);
            String id = c.path("id").asText();
            assertThat(c.path("original").path("reference").path("reportId").asText())
                    .isEqualTo(report.toString());
            if (type.equals("ISSUE"))
                assertThat(c.path("original").path("text").asText())
                        .contains("safe action")
                        .doesNotContain("PRIVATE_CODE");
            if (type.equals("SERVICE"))
                jdbc.update("DELETE FROM triage_runs WHERE id=?", report.toString());
            else
                jdbc.update(
                        "DELETE FROM "
                                + Map.of(
                                                "PR",
                                                "pr_reports",
                                                "ISSUE",
                                                "issue_reports",
                                                "CI",
                                                "ci_reports",
                                                "WORKFLOW",
                                                "development_workflows")
                                        .get(type)
                                + " WHERE id=?",
                        report.toString());
            var reopened = http.getForObject("/api/conversations/" + id, JsonNode.class);
            assertThat(reopened.path("original")).isEqualTo(c.path("original"));
            post(
                    "/" + id + "/previews",
                    Map.of("revision", 0, "question", "next", "selectedChunks", List.of()),
                    409);
        }
    }

    @Test
    void noKeyQuestionsPersistWithoutInventingAssistantAndRemainIsolated() {
        JsonNode a = create("SERVICE", service("one")), b = create("SERVICE", service("two"));
        String id = a.path("id").asText();
        JsonNode result =
                post("/" + id + "/references", Map.of("revision", 0, "question", "why?"), 200);
        assertThat(result.path("messages")).hasSize(1);
        assertThat(result.path("messages").get(0).path("role").asText()).isEqualTo("USER");
        assertThat(result.path("attempts").get(0).path("state").asText())
                .isEqualTo("REFERENCE_ONLY");
        assertThat(result.path("attempts").get(0).path("message").asText()).contains("未调用模型");
        assertThat(
                        http.getForObject(
                                        "/api/conversations/" + b.path("id").asText(),
                                        JsonNode.class)
                                .path("messages"))
                .isEmpty();
        post("/" + id + "/references", Map.of("revision", 0, "question", "stale"), 409);
        JsonNode preview =
                post(
                        "/" + id + "/previews",
                        Map.of(
                                "revision",
                                1,
                                "question",
                                "exact question",
                                "selectedChunks",
                                List.of()),
                        201);
        assertThat(preview.path("preview").path("text").asText())
                .contains("exact question", "why?", "one")
                .doesNotContain("two");
        post(
                "/" + id + "/attempts",
                Map.of("previewId", preview.path("preview").path("id").asText(), "confirmed", true),
                503);
    }

    @Test
    void rejectsUnknownCrossScopeKnowledgeOriginAndCrossOriginWrites() {
        post("", Map.of("origin", Map.of("type", "SERVICE", "reportId", UUID.randomUUID())), 404);
        post(
                "",
                Map.of(
                        "origin",
                        Map.of(
                                "type",
                                "SERVICE",
                                "reportId",
                                service("q"),
                                "projectId",
                                UUID.randomUUID())),
                409);
        post("", Map.of("origin", Map.of("type", "KNOWLEDGE", "reportId", UUID.randomUUID())), 400);
        assertThat(
                        http.postForEntity(
                                        "/api/conversations",
                                        Map.of(
                                                "origin",
                                                Map.of(
                                                        "type",
                                                        "SERVICE",
                                                        "reportId",
                                                        service("q"))),
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    JsonNode preview(String id, long revision, String question, List<?> chunks) {
        return post(
                "/" + id + "/previews",
                Map.of("revision", revision, "question", question, "selectedChunks", chunks),
                201);
    }

    JsonNode send(String id, JsonNode p, boolean docs, int code) {
        return post(
                "/" + id + "/attempts",
                Map.of(
                        "previewId",
                        p.path("preview").path("id").asText(),
                        "confirmed",
                        true,
                        "documentsConfirmed",
                        docs),
                code);
    }

    JsonNode ended(String id) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode c = http.getForObject("/api/conversations/" + id, JsonNode.class);
            if (c.path("activeAttempt").isNull()) return c;
            Thread.sleep(50);
        }
        throw new AssertionError("attempt did not finish");
    }

    final class Stub implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> mode =
                new java.util.concurrent.atomic.AtomicReference<>("OK");
        final java.util.concurrent.CountDownLatch
                received = new java.util.concurrent.CountDownLatch(1),
                release = new java.util.concurrent.CountDownLatch(1);
        volatile String body;

        Stub() throws Exception {
            server =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(
                    java.util.concurrent.Executors.newCachedThreadPool(
                            Thread.ofPlatform().daemon(true).factory()));
            server.createContext(
                    "/v1/chat/completions",
                    exchange -> {
                        try {
                            body =
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8);
                            calls.incrementAndGet();
                            received.countDown();
                            if (mode.get().equals("BLOCK"))
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            if (mode.get().equals("FAIL")) {
                                exchange.sendResponseHeaders(500, -1);
                                return;
                            }
                            var matcher =
                                    java.util.regex.Pattern.compile("report:[a-f0-9-]{36}")
                                            .matcher(body);
                            if (!matcher.find()) throw new AssertionError(body);
                            String id = mode.get().equals("FORGED") ? "unknown" : matcher.group();
                            String answer =
                                    json.writeValueAsString(
                                            Map.of(
                                                    "answer",
                                                    "证据不足，请补充新观测。",
                                                    "evidenceIds",
                                                    List.of(id)));
                            var completion =
                                    Map.of(
                                            "id",
                                            "stub",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "stub-actual",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    answer),
                                                            "finish_reason",
                                                            mode.get().equals("TRUNCATED")
                                                                    ? "length"
                                                                    : "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    100,
                                                    "completion_tokens",
                                                    20,
                                                    "total_tokens",
                                                    120));
                            byte[] bytes = json.writeValueAsBytes(completion);
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        } catch (Exception ignored) {
                        } finally {
                            exchange.close();
                        }
                    });
            server.start();
            var provider =
                    providers.create(
                            new io.github.mochiuaena.triage.settings.ProviderConfig.Input(
                                    "Conversation stub",
                                    io.github.mochiuaena.triage.settings.ProviderConfig.Protocol
                                            .OPENAI_COMPATIBLE,
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                                    "stub",
                                    "test-local-only",
                                    0.2,
                                    5,
                                    1,
                                    256,
                                    0));
            providers.select("MODEL", provider.id());
        }

        public void close() {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void exactPreviewMultiRoundContinuityUsageAndOneUseWithoutContextLeakage() throws Exception {
        try (var stub = new Stub()) {
            String id = create("SERVICE", service("original model question")).path("id").asText();
            JsonNode p = preview(id, 0, "first exact question", List.of());
            send(id, p, false, 202);
            JsonNode first = ended(id);
            assertThat(first.path("messages")).hasSize(2);
            assertThat(first.path("attempts").get(0).path("usage").path("totalTokens").asInt())
                    .isEqualTo(120);
            assertThat(
                            json.readTree(p.path("preview").path("text").asText())
                                    .path("maxOutputTokens")
                                    .asInt())
                    .isEqualTo(256);
            assertThat(json.readTree(stub.body).path("max_tokens").asInt()).isEqualTo(256);
            assertThat(json.readTree(stub.body).path("messages"))
                    .isEqualTo(
                            json.readTree(p.path("preview").path("text").asText())
                                    .path("messages"));
            assertThat(stub.body)
                    .contains("first exact question", "tool_choice", "none")
                    .doesNotContain("internalToolExecutionEnabled");
            send(id, p, false, 410);
            JsonNode next =
                    preview(id, first.path("revision").asLong(), "second question", List.of());
            assertThat(next.path("preview").path("text").asText())
                    .contains("first exact question", "证据不足", "second question");
            String other = create("SERVICE", service("isolated")).path("id").asText();
            send(other, next, false, 409);
            assertThat(preview(other, 0, "other", List.of()).path("preview").path("text").asText())
                    .doesNotContain("first exact question");
            send(id, next, false, 202);
            assertThat(ended(id).path("messages")).hasSize(4);
            assertThat(stub.calls.get()).isEqualTo(2);
        }
    }

    @Test
    void changedQuestionHistoryProviderAndDeletedMaterialCannotSend() throws Exception {
        try (var stub = new Stub()) {
            UUID report = service("fixed");
            String id = create("SERVICE", report).path("id").asText();
            JsonNode p = preview(id, 0, "old question", List.of());
            post("/" + id + "/references", Map.of("revision", 0, "question", "new history"), 200);
            send(id, p, false, 409);
            JsonNode next = preview(id, 1, "new exact question", List.of());
            providers.select("DEMO", null);
            send(id, next, false, 409);
            assertThat(stub.calls.get()).isZero();
        }
    }

    @Test
    void forgedIncompleteAndFailedOutputDoNotPublishAndCancelDiscardsLateReply() throws Exception {
        try (var stub = new Stub()) {
            for (String mode : List.of("FORGED", "TRUNCATED", "FAIL")) {
                stub.mode.set(mode);
                String id = create("SERVICE", service(mode)).path("id").asText();
                send(id, preview(id, 0, "why", List.of()), false, 202);
                var c = ended(id);
                assertThat(c.path("messages")).hasSize(1);
                if (!mode.equals("FAIL"))
                    assertThat(c.path("attempts").get(0).path("usage").path("totalTokens").asInt())
                            .isEqualTo(120);
                assertThat(c.path("attempts").get(0).path("state").asText())
                        .isEqualTo(mode.equals("FAIL") ? "FAILED" : "INVALID_OUTPUT");
            }
        }
        try (var stub = new Stub()) {
            stub.mode.set("BLOCK");
            String id = create("SERVICE", service("cancel")).path("id").asText();
            JsonNode p = preview(id, 0, "wait", List.of());
            JsonNode accepted = send(id, p, false, 202);
            assertThat(stub.received.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            post(
                    "/" + id + "/references",
                    Map.of(
                            "revision",
                            accepted.path("revision").asLong(),
                            "question",
                            "interleaved"),
                    409);
            post(
                    "/" + id + "/attempts/" + accepted.path("activeAttempt").asText() + "/cancel",
                    Map.of(),
                    200);
            stub.release.countDown();
            Thread.sleep(150);
            var c = ended(id);
            assertThat(c.path("messages")).hasSize(1);
            assertThat(c.path("attempts").get(0).path("state").asText()).isEqualTo("CANCELLED");
        }
    }

    UUID document(String text) {
        var scope =
                new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Scope(
                        "REPOSITORY", repository);
        var p =
                new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Preview(
                        UUID.randomUUID(),
                        scope,
                        "GITHUB",
                        "fixture/repo-" + repository,
                        "a".repeat(40),
                        "fixture/repo-" + repository,
                        UUID.randomUUID(),
                        0,
                        List.of(),
                        List.of(),
                        0,
                        0,
                        0,
                        true,
                        "manifest",
                        Instant.now(),
                        Instant.now().plusSeconds(600),
                        io.github.mochiuaena.triage.knowledge.KnowledgeModels.limits());
        UUID job = UUID.randomUUID();
        knowledgeStore.createJob(job, p);
        knowledgeStore.start(job);
        knowledgeStore.activate(
                job,
                p,
                List.of(
                        new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Document(
                                "guide.md",
                                io.github.mochiuaena.triage.material.MaterialService.sha(text),
                                text.length(),
                                "Guide",
                                text)));
        return knowledgeStore.versions(p.sourceId()).getFirst().id();
    }

    @Test
    void exactSelectedChunkAndDocumentDerivedHistoryAlwaysRequireSeparateConsent()
            throws Exception {
        try (var stub = new Stub()) {
            UUID version = document("# Guide\nDocMarker\n" + "unselected tail ".repeat(700));
            var chunk = knowledgeStore.chunks(version).getFirst();
            var selection = Map.of("versionId", version, "chunkId", chunk.id());
            String id = create("PR", saved("PR")).path("id").asText();
            JsonNode p = preview(id, 0, "use documents", List.of(selection));
            assertThat(p.path("context").path("documentsRequired").asBoolean()).isTrue();
            assertThat(
                            p.path("context")
                                    .path("citations")
                                    .get(1)
                                    .path("chunk")
                                    .path("text")
                                    .asText())
                    .isEqualTo(chunk.text());
            send(id, p, false, 400);
            assertThat(stub.calls.get()).isZero();
            send(id, p, true, 202);
            JsonNode first = ended(id);
            JsonNode history =
                    preview(
                            id,
                            first.path("revision").asLong(),
                            "history without new chunks",
                            List.of());
            assertThat(history.path("context").path("documentsRequired").asBoolean()).isTrue();
            send(id, history, false, 400);
            assertThat(stub.calls.get()).isEqualTo(1);
            jdbc.update(
                    "DELETE FROM knowledge_active_documents WHERE version_id=?",
                    version.toString());
            send(id, history, true, 409);
            assertThat(stub.calls.get()).isEqualTo(1);
            assertThat(
                            http.getForObject("/api/conversations/" + id, JsonNode.class)
                                    .path("messages")
                                    .get(0)
                                    .path("citations")
                                    .get(1)
                                    .path("chunk")
                                    .path("text")
                                    .asText())
                    .isEqualTo(chunk.text());
        }
    }

    @Test
    void concurrentAdmissionAndHistoryCapsAreExplicit() throws Exception {
        try (var stub = new Stub()) {
            stub.mode.set("BLOCK");
            String id = create("SERVICE", service("concurrent")).path("id").asText();
            JsonNode p = preview(id, 0, "one question", List.of());
            var h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("X-Triage-Conversation", "1");
            var input =
                    new HttpEntity<>(
                            Map.of(
                                    "previewId",
                                    p.path("preview").path("id").asText(),
                                    "confirmed",
                                    true),
                            h);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var one =
                        executor.submit(
                                () ->
                                        http.postForEntity(
                                                "/api/conversations/" + id + "/attempts",
                                                input,
                                                JsonNode.class));
                var two =
                        executor.submit(
                                () ->
                                        http.postForEntity(
                                                "/api/conversations/" + id + "/attempts",
                                                input,
                                                JsonNode.class));
                assertThat(
                                List.of(
                                        one.get().getStatusCode().value(),
                                        two.get().getStatusCode().value()))
                        .contains(202)
                        .anyMatch(code -> code == 409 || code == 410);
            }
            assertThat(stub.received.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            stub.release.countDown();
            assertThat(ended(id).path("messages")).hasSize(2);
            assertThat(stub.calls.get()).isEqualTo(1);
        }
        String id = create("SERVICE", service("bounded")).path("id").asText();
        for (int i = 0; i < 14; i++)
            post("/" + id + "/references", Map.of("revision", i, "question", "question-" + i), 200);
        JsonNode p = preview(id, 14, "next", List.of());
        assertThat(p.path("context").path("history")).hasSize(12);
        assertThat(p.path("context").path("omissions").toString()).contains("省略 2 条");
        assertThat(http.getForObject("/api/conversations/" + id, JsonNode.class).path("messages"))
                .hasSize(14);
        post("/" + id + "/previews", Map.of("revision", 14, "question", "x".repeat(4001)), 400);
    }

    @Test
    void crossScopeWrongVersionAndLegacyServiceCannotAttachDocuments() throws Exception {
        UUID version = document("unique document");
        var chunk = knowledgeStore.chunks(version).getFirst();
        String legacy = create("SERVICE", service("legacy")).path("id").asText();
        post(
                "/" + legacy + "/previews",
                Map.of(
                        "revision",
                        0,
                        "question",
                        "docs",
                        "selectedChunks",
                        List.of(Map.of("versionId", version, "chunkId", chunk.id()))),
                409);
        String id = create("PR", saved("PR")).path("id").asText();
        post(
                "/" + id + "/previews",
                Map.of(
                        "revision",
                        0,
                        "question",
                        "docs",
                        "selectedChunks",
                        List.of(Map.of("versionId", version, "chunkId", UUID.randomUUID()))),
                404);
        UUID other = UUID.randomUUID();
        jdbc.update(
                "UPDATE knowledge_versions SET scope_id=? WHERE id=?",
                other.toString(),
                version.toString());
        post(
                "/" + id + "/previews",
                Map.of(
                        "revision",
                        0,
                        "question",
                        "docs",
                        "selectedChunks",
                        List.of(Map.of("versionId", version, "chunkId", chunk.id()))),
                409);
    }
}
