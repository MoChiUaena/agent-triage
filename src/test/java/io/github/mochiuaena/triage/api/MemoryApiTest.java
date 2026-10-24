package io.github.mochiuaena.triage.api;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;

import io.github.mochiuaena.triage.conversation.*;
import io.github.mochiuaena.triage.conversation.ConversationModels.*;
import io.github.mochiuaena.triage.material.MaterialModels.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

import java.time.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.url=jdbc:h2:mem:memory-api;DB_CLOSE_DELAY=-1",
            "triage.settings.key-file=target/memory-api-key"
        })
class MemoryApiTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("TRIAGE_MEMORY_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv("TRIAGE_MEMORY_TEST_DB_USER"));
            r.add(
                    "spring.datasource.password",
                    () -> System.getenv("TRIAGE_MEMORY_TEST_DB_PASSWORD"));
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ConversationStore conversations;
    @Autowired io.github.mochiuaena.triage.source.SourceProjectRepository projects;
    @Autowired io.github.mochiuaena.triage.knowledge.KnowledgeRepository knowledge;
    UUID repo;

    @BeforeEach
    void fixture() {
        repo = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot)"
                    + " VALUES(?,?,?,?,?,?)",
                repo.toString(),
                "fixture",
                "memory-" + repo,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "{}");
    }

    String scope() {
        return "scopeKind=REPOSITORY&scopeId=" + repo;
    }

    Map<String, Object> input(String text) {
        return new HashMap<>(
                Map.of(
                        "scopeKind",
                        "REPOSITORY",
                        "scopeId",
                        repo,
                        "topic",
                        "经验",
                        "content",
                        text,
                        "note",
                        "个人陈述"));
    }

    ResponseEntity<JsonNode> write(String route, Object input, String marker, String origin) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (marker != null) h.set(marker, "1");
        if (origin != null) h.setOrigin(origin);
        return http.postForEntity(
                "/api/memories" + route, new HttpEntity<>(input, h), JsonNode.class);
    }

    JsonNode post(String route, Object input, int code) {
        var result = write(route, input, "X-Triage-Memory", null);
        assertThat(result.getStatusCode().value()).as("POST %s", route).isEqualTo(code);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        return result.getBody();
    }

    JsonNode get(String route, int code) {
        var r = http.getForEntity("/api/memories" + route, JsonNode.class);
        assertThat(r.getStatusCode().value()).as("GET %s", route).isEqualTo(code);
        return r.getBody();
    }

    Material original(UUID repository) {
        return new Material(
                new Reference(
                        Type.ISSUE,
                        UUID.randomUUID(),
                        new Scope(null, repository, null),
                        "saved",
                        "report-hash",
                        Instant.now(),
                        "UNCONFIRMED",
                        "/development.html?report=history"),
                "original historical report",
                List.of("safe"),
                List.of("not source"));
    }

    Conversation withMessage(Material original, String text) {
        var c = conversations.create(original);
        var id = UUID.randomUUID();
        conversations.admit(
                c.id(),
                0,
                new Message(
                        UUID.randomUUID(),
                        id,
                        "USER",
                        text,
                        Instant.now(),
                        List.of(new Citation("R1", original, null)),
                        false),
                new Attempt(
                        id,
                        "REFERENCES",
                        "local",
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null),
                false);
        return conversations.require(c.id());
    }

    @Test
    void httpViewingChainCandidateEditReviewConfirmDisableAndHistoricalReopen() {
        var c = withMessage(original(repo), "confirmed 仅是文档用词 <script>unsafe</script>");
        var saved =
                post(
                        "/conversation-candidates",
                        Map.of(
                                "conversationId",
                                c.id(),
                                "messageIds",
                                List.of(c.messages().getFirst().id())),
                        201);
        String id = saved.path("id").asText();
        assertThat(saved.path("status").asText()).isEqualTo("CANDIDATE");
        assertThat(saved.at("/body/basis/messages/0/text").asText()).contains("<script>");
        get("/" + id + "?" + scope(), 200);
        var edit = input("reviewed text");
        edit.put("revision", 1);
        var edited = post("/" + id + "/edit", edit, 200);
        assertThat(edited.at("/body/basis/conversationId").asText()).isEqualTo(c.id().toString());
        var action =
                new HashMap<String, Object>(
                        Map.of(
                                "scopeKind",
                                "REPOSITORY",
                                "scopeId",
                                repo,
                                "revision",
                                2,
                                "confirmed",
                                true,
                                "bodySha256",
                                edited.path("bodySha256").asText()));
        assertThat(get("?" + scope(), 200)).isEmpty();
        post("/" + id + "/confirm", action, 200);
        assertThat(get("?" + scope(), 200)).hasSize(1);
        action.put("revision", 3);
        post("/" + id + "/disable", action, 200);
        assertThat(get("?" + scope(), 200)).isEmpty();
        jdbc.update("DELETE FROM github_repositories WHERE id=?", repo.toString());
        var historical = get("/" + id + "/versions/1?" + scope(), 200);
        assertThat(historical.at("/body/basis/messages/0/citations/0/report/text").asText())
                .isEqualTo("original historical report");
        assertThat(get("/" + id + "/history?" + scope(), 200)).hasSize(4);
        action.put("revision", 4);
        post("/" + id + "/confirm", action, 404);
        assertThat(conversations.require(c.id())).isEqualTo(c);
    }

    @Test
    void candidateCannotForgeMessageOrLegacyServiceIdentity() {
        var c = withMessage(original(repo), "text");
        post(
                "/conversation-candidates",
                Map.of("conversationId", c.id(), "messageIds", List.of(UUID.randomUUID())),
                400);
        var legacy =
                new Material(
                        new Reference(
                                Type.SERVICE,
                                UUID.randomUUID(),
                                new Scope(null, null, "service"),
                                "snapshot",
                                "hash",
                                Instant.now(),
                                "UNCONFIRMED",
                                "/?run=old"),
                        "old",
                        List.of(),
                        List.of());
        var old = withMessage(legacy, "legacy");
        post(
                "/conversation-candidates",
                Map.of(
                        "conversationId",
                        old.id(),
                        "messageIds",
                        List.of(old.messages().getFirst().id())),
                400);
        assertThat(get("?" + scope() + "&status=ALL", 200)).isEmpty();
    }

    @Test
    void originMarkerBoundsAndExactConfirmationAreEnforced() {
        assertThat(write("", input("text"), null, null).getStatusCode().value()).isEqualTo(403);
        assertThat(
                        write("", input("text"), "X-Triage-Memory", "https://evil.invalid")
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
        post("", input("x".repeat(4001)), 400);
        var first = post("", input("one"), 201);
        String id = first.path("id").asText();
        var action =
                new HashMap<String, Object>(
                        Map.of(
                                "scopeKind",
                                "REPOSITORY",
                                "scopeId",
                                repo,
                                "revision",
                                1,
                                "confirmed",
                                false,
                                "bodySha256",
                                first.path("bodySha256").asText()));
        post("/" + id + "/confirm", action, 400);
        action.put("confirmed", true);
        action.put("bodySha256", "false");
        post("/" + id + "/confirm", action, 409);
        action.put("bodySha256", first.path("bodySha256").asText());
        post("/" + id + "/confirm", action, 200);
        post("/" + id + "/confirm", action, 409);
        get("/" + id + "?scopeKind=REPOSITORY&scopeId=" + UUID.randomUUID(), 409);
        get("?" + scope() + "&limit=101", 400);
    }

    @Test
    void referencesAreServerResolvedAndWrongScopeRejectedBeforeMutation() throws Exception {
        UUID report = UUID.randomUUID();
        var payload =
                Map.of(
                        "id",
                        report,
                        "repositoryId",
                        repo,
                        "createdAt",
                        Instant.now(),
                        "repository",
                        "fixture/repo",
                        "proposal",
                        Map.of("summary", "server saved summary"));
        jdbc.update(
                "INSERT INTO issue_reports(id,repository_id,created_at,payload) VALUES(?,?,?,?)",
                report.toString(),
                repo.toString(),
                OffsetDateTime.now(),
                json.writeValueAsString(payload));
        var body = input("experience");
        body.put(
                "references",
                List.of(Map.of("type", "ISSUE", "reportId", report, "saved", "FORGED")));
        var first = post("", body, 201);
        assertThat(first.at("/body/basis/references/0/saved/text").asText())
                .contains("server saved summary")
                .doesNotContain("FORGED");
        body.put("scopeId", UUID.randomUUID());
        post("", body, 404);
        UUID other = repo;
        fixture();
        body.put("scopeId", repo);
        post("", body, 409);
        repo = other;
        jdbc.update("DELETE FROM issue_reports WHERE id=?", report.toString());
        assertThat(
                        get("/" + first.path("id").asText() + "/versions/1?" + scope(), 200)
                                .at("/body/basis/references/0/saved/text")
                                .asText())
                .contains("server saved summary");
        post("", body, 404);
    }

    @Test
    void sameServiceAndNameNeverInheritOldProjectMemory() {
        var index =
                new io.github.mochiuaena.triage.source.SourceModels.Index(
                        "fixture", Instant.now(), 0, 0, 0, List.of());
        String service = "memory-" + UUID.randomUUID();
        var p = projects.create("same name", service, "fixture", index);
        var body = input("first project experience");
        body.put("scopeKind", "PROJECT");
        body.put("scopeId", p.id());
        var first = post("", body, 201);
        String id = first.path("id").asText();
        assertThat(first.at("/scope/service").asText()).isEqualTo(service);
        var action =
                new HashMap<String, Object>(
                        Map.of(
                                "scopeKind",
                                "PROJECT",
                                "scopeId",
                                p.id(),
                                "revision",
                                1,
                                "confirmed",
                                true,
                                "bodySha256",
                                first.path("bodySha256").asText()));
        post("/" + id + "/confirm", action, 200);
        projects.delete(p.id(), p.revision());
        var replacement = projects.create("same name", service, "fixture", index);
        assertThat(get("?scopeKind=PROJECT&scopeId=" + replacement.id(), 200)).isEmpty();
        get("/" + id + "?scopeKind=PROJECT&scopeId=" + replacement.id(), 409);
        get("/" + id + "/versions/1?scopeKind=PROJECT&scopeId=" + p.id(), 200);
        action.put("revision", 2);
        post("/" + id + "/disable", action, 404);
        body.put("scopeId", replacement.id());
        body.put("service", "unrelated");
        post("", body, 400);
        projects.delete(replacement.id(), replacement.revision());
    }

    @Test
    void exactKnowledgeChunkScopeAndHistoricalConfirmationDoNotRequireActiveSource() {
        var scope =
                new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Scope("REPOSITORY", repo);
        var preview =
                new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Preview(
                        UUID.randomUUID(),
                        scope,
                        "GITHUB",
                        "fixture/memory-" + repo,
                        "a".repeat(40),
                        "fixture/memory-" + repo,
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
        knowledge.createJob(job, preview);
        knowledge.start(job);
        String text = "# Historical guide\nExact saved knowledge.";
        knowledge.activate(
                job,
                preview,
                List.of(
                        new io.github.mochiuaena.triage.knowledge.KnowledgeModels.Document(
                                "guide.md",
                                io.github.mochiuaena.triage.material.MaterialService.sha(text),
                                text.length(),
                                "Guide",
                                text)));
        var version = knowledge.versions(preview.sourceId()).getFirst();
        var chunk = knowledge.chunks(version.id()).getFirst();
        var body = input("knowledge experience");
        body.put(
                "references",
                List.of(
                        Map.of(
                                "type",
                                "CHUNK",
                                "versionId",
                                version.id(),
                                "chunkId",
                                chunk.id(),
                                "text",
                                "FORGED")));
        var first = post("", body, 201);
        assertThat(first.at("/body/basis/references/0/saved/text").asText()).isEqualTo(text);
        UUID originalRepo = repo;
        fixture();
        body.put("scopeId", repo);
        post("", body, 409);
        repo = originalRepo;
        var source = knowledge.require(preview.sourceId());
        knowledge.action(source.id(), scope, source.revision(), "DELETE");
        String id = first.path("id").asText();
        var confirmed =
                post(
                        "/" + id + "/confirm",
                        Map.of(
                                "scopeKind",
                                "REPOSITORY",
                                "scopeId",
                                repo,
                                "revision",
                                1,
                                "confirmed",
                                true,
                                "bodySha256",
                                first.path("bodySha256").asText()),
                        200);
        assertThat(confirmed.path("confirmationProvenance").asText()).contains("历史");
        assertThat(
                        get("/" + id + "/versions/1?" + scope(), 200)
                                .at("/body/basis/references/0/saved/text")
                                .asText())
                .isEqualTo(text);
    }

    @Test
    void oversizeConversationBasisRejectsInsteadOfDroppingProvenance() {
        var c = withMessage(original(repo), "x".repeat(49000));
        post(
                "/conversation-candidates",
                Map.of(
                        "conversationId",
                        c.id(),
                        "messageIds",
                        List.of(c.messages().getFirst().id())),
                400);
        assertThat(get("?" + scope() + "&status=ALL", 200)).isEmpty();
        var bounded = withMessage(original(repo), "x".repeat(5000));
        var candidate =
                post(
                        "/conversation-candidates",
                        Map.of(
                                "conversationId",
                                bounded.id(),
                                "messageIds",
                                List.of(bounded.messages().getFirst().id())),
                        201);
        assertThat(candidate.at("/body/content").asText()).hasSize(4000);
        assertThat(candidate.at("/body/basis/provenance").asText()).contains("前4000");
        assertThat(candidate.at("/body/basis/messages/0/text").asText()).hasSize(5000);
    }

    @Test
    void serviceBindingDriftRejectsCandidateEditAndConfirmButAllowsWithdrawal() {
        var index =
                new io.github.mochiuaena.triage.source.SourceModels.Index(
                        "fixture", Instant.now(), 0, 0, 0, List.of());
        String service = "old-" + UUID.randomUUID();
        var project = projects.create("bound", service, "fixture", index);
        var body = input("old service experience");
        body.put("scopeKind", "PROJECT");
        body.put("scopeId", project.id());
        var first = post("", body, 201);
        String id = first.path("id").asText();
        var original =
                new Material(
                        new Reference(
                                Type.SERVICE,
                                UUID.randomUUID(),
                                new Scope(project.id(), null, service),
                                "saved",
                                "hash",
                                Instant.now(),
                                "UNCONFIRMED",
                                "/?run=old"),
                        "saved report",
                        List.of(),
                        List.of());
        var conversation = withMessage(original, "old service message");
        String replacementService = "new-" + UUID.randomUUID();
        projects.update(project, project.name(), replacementService, project.root(), index);
        post(
                "/conversation-candidates",
                Map.of(
                        "conversationId",
                        conversation.id(),
                        "messageIds",
                        List.of(conversation.messages().getFirst().id())),
                409);
        body.put("revision", 1);
        post("/" + id + "/edit", body, 409);
        var action =
                new HashMap<String, Object>(
                        Map.of(
                                "scopeKind",
                                "PROJECT",
                                "scopeId",
                                project.id(),
                                "revision",
                                1,
                                "confirmed",
                                true,
                                "bodySha256",
                                first.path("bodySha256").asText()));
        post("/" + id + "/confirm", action, 409);
        String q = "scopeKind=PROJECT&scopeId=" + project.id();
        var summaries = get("?" + q + "&status=ALL", 200);
        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).at("/scope/service").asText()).isEqualTo(service);
        assertThat(get("?" + q + "&status=ALL&service=" + replacementService, 200)).isEmpty();
        action.put("service", service);
        post("/" + id + "/disable", action, 200);
        action.put("revision", 2);
        post("/" + id + "/delete", action, 200);
        assertThat(get("/" + id + "/versions/1?" + q, 200).at("/scope/service").asText())
                .isEqualTo(service);
        projects.delete(project.id(), projects.require(project.id()).revision());
    }

    @Test
    void clippingKeepsCompleteUnicodeCharactersAndNulInputIsRejected() {
        var conversation = withMessage(original(repo), "x".repeat(3999) + "😀tail");
        var saved =
                post(
                        "/conversation-candidates",
                        Map.of(
                                "conversationId",
                                conversation.id(),
                                "messageIds",
                                List.of(conversation.messages().getFirst().id())),
                        201);
        String content = saved.at("/body/content").asText();
        assertThat(content).hasSize(3999);
        assertThat(Character.isHighSurrogate(content.charAt(content.length() - 1))).isFalse();
        assertThat(saved.at("/body/basis/messages/0/text").asText()).endsWith("😀tail");
        assertThat(saved.at("/body/basis/provenance").asText()).contains("裁剪");
    }

    @Test
    void nulInUserAuthoredFieldsIsExplicitlyRejected() {
        for (String field : List.of("topic", "content", "note")) {
            var body = input("safe");
            body.put(field, "before" + (char) 0 + "after");
            post("", body, 400);
        }
    }
}
