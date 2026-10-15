package io.github.mochiuaena.triage.api;

import static org.assertj.core.api.Assertions.*;

import io.github.mochiuaena.triage.source.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.time.*;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "triage.mode=DEMO",
            "spring.datasource.username=${TRIAGE_KNOWLEDGE_TEST_DB_USER:sa}",
            "spring.datasource.password=${TRIAGE_KNOWLEDGE_TEST_DB_PASSWORD:}",
            "spring.datasource.url=${TRIAGE_KNOWLEDGE_TEST_DB_URL:jdbc:h2:mem:knowledge;DB_CLOSE_DELAY=-1}",
            "triage.settings.key-file=target/knowledge-test-key"
        })
class KnowledgeApiTest {
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    UUID project;

    @BeforeEach
    void setup() throws Exception {
        project =
                projects.create(
                                "Knowledge fixture",
                                null,
                                directory.toString(),
                                new SourceModels.Index(
                                        "fixture", Instant.now(), 0, 0, 0, List.of()))
                        .id();
        Files.writeString(directory.resolve("README.md"), "# Architecture\nStable documentation\n");
    }

    HttpHeaders headers() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Triage-Knowledge", "1");
        return h;
    }

    ResponseEntity<Map> write(String path, Object value, String method) {
        return http.exchange(
                "/api/knowledge" + path,
                HttpMethod.valueOf(method),
                new HttpEntity<>(value, headers()),
                Map.class);
    }

    Map preview() {
        var r =
                write(
                        "/previews",
                        Map.of(
                                "scopeKind",
                                "PROJECT",
                                "scopeId",
                                project,
                                "directory",
                                directory.toString()),
                        "POST");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    Map imported(Map preview) throws Exception {
        var r = write("/imports", Map.of("previewId", preview.get("id")), "POST");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = r.getBody().get("id").toString();
        Map job = null;
        for (int i = 0; i < 150; i++) {
            job = http.getForObject("/api/knowledge/jobs/" + id, Map.class);
            if (!List.of("QUEUED", "RUNNING").contains(job.get("state"))) break;
            Thread.sleep(40);
        }
        assertThat(job.get("state")).isEqualTo("SUCCEEDED");
        return job;
    }

    String query() {
        return "?scopeKind=PROJECT&scopeId=" + project;
    }

    List<Map> sources() {
        return http.getForObject("/api/knowledge/sources" + query(), List.class);
    }

    @Test
    void incrementalVersionsSoftDeleteAndHistoricalScopeSurviveRegistrationRemoval()
            throws Exception {
        Map first = preview();
        assertThat(first.toString()).contains("README.md", "Stable documentation");
        imported(first);
        Map source = sources().getFirst();
        String sid = source.get("id").toString();
        var versions =
                http.getForObject(
                        "/api/knowledge/sources/" + sid + "/versions" + query(), List.class);
        assertThat(versions).hasSize(1);
        String version = ((Map) versions.getFirst()).get("id").toString();
        imported(preview());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_versions WHERE source_id=?",
                                Integer.class,
                                sid))
                .isEqualTo(1);
        Files.writeString(directory.resolve("README.md"), "# Changed\nNew content\n");
        Files.writeString(directory.resolve("guide.txt"), "second file");
        imported(preview());
        Files.delete(directory.resolve("README.md"));
        imported(preview());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_versions WHERE source_id=?",
                                Integer.class,
                                sid))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_active_documents WHERE source_id=?",
                                Integer.class,
                                sid))
                .isEqualTo(1);
        source = sources().getFirst();
        assertThat(
                        write(
                                        "/sources/" + sid,
                                        Map.of(
                                                "scopeKind",
                                                "PROJECT",
                                                "scopeId",
                                                project,
                                                "revision",
                                                source.get("revision"),
                                                "action",
                                                "DELETE",
                                                "confirmed",
                                                true),
                                        "POST")
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_active_documents WHERE source_id=?",
                                Integer.class,
                                sid))
                .isZero();
        assertThat(
                        http.getForEntity("/api/knowledge/versions/" + version + query(), Map.class)
                                .getBody()
                                .get("text"))
                .isEqualTo("# Architecture\nStable documentation\n");
        projects.delete(project, projects.require(project).revision());
        assertThat(
                        http.getForEntity("/api/knowledge/versions/" + version + query(), Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        http.getForEntity(
                                        "/api/knowledge/versions/"
                                                + version
                                                + "?scopeKind=PROJECT&scopeId="
                                                + UUID.randomUUID(),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity("/api/knowledge/sources" + query(), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stalePreviewCannotActivateAndSeparateDocsRootIsAllowed() throws Exception {
        var p = preview();
        Files.writeString(directory.resolve("README.md"), "changed after preview");
        var submitted = write("/imports", Map.of("previewId", p.get("id")), "POST");
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map job = null;
        for (int i = 0; i < 150; i++) {
            job =
                    http.getForObject(
                            "/api/knowledge/jobs/" + submitted.getBody().get("id"), Map.class);
            if (!List.of("QUEUED", "RUNNING").contains(job.get("state"))) break;
            Thread.sleep(30);
        }
        assertThat(job.get("state")).isEqualTo("FAILED");
        assertThat(sources()).isEmpty();
        Path docs = Files.createTempDirectory("knowledge-docs-");
        try {
            Files.writeString(docs.resolve("manual.txt"), "separate docs");
            var response =
                    write(
                            "/previews",
                            Map.of(
                                    "scopeKind",
                                    "PROJECT",
                                    "scopeId",
                                    project,
                                    "directory",
                                    docs.toString()),
                            "POST");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            imported(response.getBody());
        } finally {
            Files.deleteIfExists(docs.resolve("manual.txt"));
            Files.deleteIfExists(docs);
        }
    }

    @Test
    void exclusionsLimitsAndAccessMarkerAreEnforced() throws Exception {
        Files.writeString(directory.resolve("secret.md"), "api_key=must-never-import");
        Files.createDirectories(directory.resolve("target"));
        Files.writeString(directory.resolve("target/build.md"), "excluded");
        Files.write(directory.resolve("invalid.txt"), new byte[] {(byte) 0xc3, 0x28});
        Files.writeString(directory.resolve("big.txt"), "x".repeat(262145));
        var preview = preview();
        assertThat(preview.toString())
                .doesNotContain("must-never-import")
                .contains("CREDENTIAL", "DIRECTORY", "UTF8", "FILE_BYTES");
        assertThat((List) preview.get("files")).hasSize(1);
        assertThat(
                        http.postForEntity(
                                        "/api/knowledge/previews",
                                        new HttpEntity<>(
                                                Map.of("scopeKind", "PROJECT", "scopeId", project)),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        write(
                                        "/previews",
                                        Map.of(
                                                "scopeKind",
                                                "PROJECT",
                                                "scopeId",
                                                project,
                                                "directory",
                                                directory.resolve("../").toString()),
                                        "POST")
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var cross = headers();
        cross.setOrigin("https://evil.example");
        assertThat(
                        http.exchange(
                                        "/api/knowledge/sources" + query(),
                                        HttpMethod.GET,
                                        new HttpEntity<>(cross),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void knowledgeMaterialUsesImmutableVersionAndSeparateDocumentConsent() throws Exception {
        Files.writeString(
                directory.resolve("README.md"),
                "# Architecture\nStable documentation\n" + "x".repeat(20000));
        imported(preview());
        String sid = sources().getFirst().get("id").toString();
        String vid =
                jdbc.queryForObject(
                        "SELECT id FROM knowledge_versions WHERE source_id=?", String.class, sid);
        var h = headers();
        h.set("X-Triage-Material", "1");
        var selection = Map.of("type", "KNOWLEDGE", "reportId", vid, "projectId", project);
        var result =
                http.postForEntity(
                        "/api/materials/resolve", new HttpEntity<>(selection, h), Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("text").toString()).contains("Stable documentation");
        assertThat(result.getBody().get("text").toString()).hasSizeLessThanOrEqualTo(16000);
        assertThat(result.getBody().get("omissions").toString()).contains("裁剪");
        var p =
                http.postForEntity(
                        "/api/materials/previews",
                        new HttpEntity<>(Map.of("references", List.of(selection)), h),
                        Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(
                        http.postForEntity(
                                        "/api/materials/previews/"
                                                + p.getBody().get("id")
                                                + "/consume",
                                        new HttpEntity<>(Map.of("confirmed", true), h),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        http.postForEntity(
                                        "/api/materials/resolve",
                                        new HttpEntity<>(
                                                Map.of("type", "KNOWLEDGE", "reportId", vid), h),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
