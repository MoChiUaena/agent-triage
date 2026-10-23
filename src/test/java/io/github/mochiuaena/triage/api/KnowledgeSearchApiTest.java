package io.github.mochiuaena.triage.api;

import static org.assertj.core.api.Assertions.*;
import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

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
import java.nio.file.*;
import java.time.*;
import java.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO",
    "spring.datasource.username=${TRIAGE_KNOWLEDGE_TEST_DB_USER:sa}",
    "spring.datasource.password=${TRIAGE_KNOWLEDGE_TEST_DB_PASSWORD:}",
    "spring.datasource.url=${TRIAGE_KNOWLEDGE_TEST_DB_URL:jdbc:h2:mem:knowledge-search;DB_CLOSE_DELAY=-1}",
    "triage.settings.key-file=target/knowledge-search-test-key"
})
class KnowledgeSearchApiTest {
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectRepository projects;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository store;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    Scope first, second, repository;

    @BeforeEach void setup() {
        var index = new SourceModels.Index("fixture", Instant.now(), 0, 0, 0, List.of());
        first = new Scope("PROJECT", projects.create("Search one", "search-" + UUID.randomUUID(), directory.toString(), index).id());
        second = new Scope("PROJECT", projects.create("Search two", null, directory.toString(), index).id());
        repository = new Scope("REPOSITORY", UUID.randomUUID());
        var time = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO github_repositories(id,owner_name,repository_name,registered_at,checked_at,snapshot) VALUES(?,?,?,?,?,?)",
            repository.id().toString(), "fixture", "search-" + repository.id(), time, time, "{}");
    }

    // Persist through the real K1 activation/chunk builder, with a fixed offline repository identity.
    UUID activate(Scope scope, UUID source, List<Document> documents) {
        var prior = source == null ? null : store.require(source);
        String root = scope.kind().equals("PROJECT") ? (prior == null ? directory + "/docs-" + UUID.randomUUID() : prior.root()) : "fixture/search-" + scope.id();
        String stamp = scope.kind().equals("PROJECT") ? "1:" + directory : root;
        var p = new Preview(UUID.randomUUID(), scope, scope.kind().equals("PROJECT") ? "LOCAL" : "GITHUB", root,
            scope.kind().equals("PROJECT") ? null : "a".repeat(40), stamp, source == null ? UUID.randomUUID() : source,
            prior == null ? 0 : prior.revision(), List.of(), List.of(), 0, 0, 0, true, "manifest", Instant.now(), Instant.now().plusSeconds(600), limits());
        UUID job = UUID.randomUUID(); store.createJob(job, p); store.start(job); store.activate(job, p, documents);
        return p.sourceId();
    }
    Document doc(String path, String title, String text) {
        return new Document(path, MaterialService.sha(text), text.length(), title, text);
    }
    String scope(Scope s) { return "scopeKind=" + s.kind() + "&scopeId=" + s.id(); }
    ResponseEntity<Map> search(Scope s, String query, String extra) {
        return http.getForEntity("/api/knowledge/search?" + scope(s) + "&query={query}" + extra, Map.class, query);
    }
    List<Map> hits(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) r.getBody().get("results");
    }
    ResponseEntity<Map> citation(Map hit, Scope s) {
        return http.getForEntity("/api/knowledge/versions/" + hit.get("versionId") + "/chunks/" + hit.get("chunkId") + "?" + scope(s), Map.class);
    }

    @Test void scopesAndBoundServiceNeverLeakIdenticalTokensWithoutProvider() {
        activate(first, null, List.of(doc("one.md", "One", "shared Token")));
        activate(second, null, List.of(doc("two.md", "Two", "shared Token")));
        activate(repository, null, List.of(doc("docs/repo.md", "Repo", "shared Token")));
        assertThat(hits(search(first, "TOKEN", ""))).extracting(h -> h.get("path")).containsExactly("one.md");
        assertThat(hits(search(second, "token", ""))).extracting(h -> h.get("path")).containsExactly("two.md");
        var repoHit = hits(search(repository, "token", "")).getFirst();
        assertThat(repoHit.get("sourceUrl").toString()).contains("/blob/" + "a".repeat(40) + "/docs/repo.md");
        String service = projects.require(first.id()).service();
        assertThat(hits(search(first, "token", "&service=" + service))).hasSize(1);
        assertThat(search(second, "token", "&service=" + service).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(search(repository, "token", "&service=" + service).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void rankingChineseMatchingTiesFiltersAndLimitAreDeterministic() {
        activate(first, null, List.of(doc("a.md", "连接池", "# 连接池\n诊断方法"), doc("b.md", "Other", "连接池正文"),
            doc("c.txt", "Other", "连接池正文"), doc("d.md", "Other", "连接池正文")));
        var r = search(first, "连接池", "&limit=3");
        assertThat(hits(r)).extracting(h -> h.get("path")).containsExactly("a.md", "b.md", "c.txt");
        assertThat(hits(search(first, "连接池", "&limit=3"))).isEqualTo(hits(r));
        assertThat(hits(search(first, "连接池", "&docType=TEXT"))).extracting(h -> h.get("path")).containsExactly("c.txt");
        assertThat(hits(search(first, "连接池", "&docType=MARKDOWN"))).hasSize(3);
        assertThat(hits(search(first, "连接池 诊断", ""))).extracting(h -> h.get("path")).containsExactly("a.md");
        assertThat(hits(search(first, "C", "&docType=TEXT"))).extracting(h -> h.get("path")).containsExactly("c.txt");
        assertThat(hits(search(first, "不存在", ""))).isEmpty();
        assertThat(r.getBody()).containsEntry("recallMethod", "KEYWORD").containsEntry("truncated", false);
    }

    @Test void onlyCurrentDocumentsSearchButExactScopedOldChunkSurvivesUpdateDeleteAndRegistrationRemoval() {
        String oldText = "# Guide\nOldMarker\n保存原文";
        UUID source = activate(first, null, List.of(doc("guide.md", "Guide", oldText)));
        Map old = hits(search(first, "OldMarker", "")).getFirst();
        assertThat(old).containsKeys("chunkId", "versionId", "sourceId", "scope", "sha256", "chunkSha256", "importedAt", "startLine", "endLine", "originalUrl", "score");
        assertThat(old.get("excerpt").toString()).contains("OldMarker");
        assertThat(citation(old, second).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        activate(first, source, List.of(doc("guide.md", "Guide", "NewMarker")));
        assertThat(hits(search(first, "OldMarker", ""))).isEmpty();
        Map current = hits(search(first, "NewMarker", "")).getFirst();
        assertThat(citation(old, first).getBody()).containsEntry("text", oldText).containsEntry("startLine", 1).containsEntry("endLine", 3);
        assertThat(http.getForEntity("/api/knowledge/versions/" + current.get("versionId") + "/chunks/" + old.get("chunkId") + "?" + scope(first), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        knowledge.action(source, new Action(first.kind(), first.id(), store.require(source).revision(), "UNBIND", true));
        assertThat(hits(search(first, "NewMarker", ""))).isEmpty();
        activate(first, source, List.of(doc("guide.md", "Guide", "NewMarker")));
        knowledge.action(source, new Action(first.kind(), first.id(), store.require(source).revision(), "DELETE", true));
        assertThat(hits(search(first, "NewMarker", ""))).isEmpty();
        projects.delete(first.id(), projects.require(first.id()).revision());
        assertThat(search(first, "NewMarker", "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(citation(old, first).getBody()).containsEntry("text", oldText);
    }

    @Test void removedFileAndRemovedRepositoryDoNotEnterCurrentSearch() {
        UUID sid = activate(repository, null, List.of(doc("docs/a.md", "A", "needle")));
        Map old = hits(search(repository, "needle", "")).getFirst();
        activate(repository, sid, List.of());
        assertThat(hits(search(repository, "needle", ""))).isEmpty();
        activate(repository, sid, List.of(doc("docs/a.md", "A", "needle")));
        jdbc.update("DELETE FROM github_repositories WHERE id=?", repository.id().toString());
        assertThat(search(repository, "needle", "").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(citation(old, repository).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void excerptContainsLateMatchRatherThanOnlyDocumentPrefix() {
        activate(first, null, List.of(doc("guide.md", "Guide", "x".repeat(1600) + "\nlateNeedle\n" + "z".repeat(300))));
        var hit = hits(search(first, "LATENEEDLE", "")).getFirst();
        assertThat(hit.get("excerpt").toString()).contains("lateNeedle").hasSizeLessThanOrEqualTo(360);
        assertThat(citation(hit, first).getBody().get("text").toString()).hasSize(1912);
    }

    @Test void expandingUnicodeCaseFoldKeepsExcerptInOriginalCoordinates() {
        String original = "İ".repeat(1000) + "needle";
        activate(first, null, List.of(doc("unicode.md", "Unicode", original)));
        var hit = hits(search(first, "NEEDLE", "")).getFirst();
        assertThat(hit.get("excerpt").toString()).endsWith("needle").hasSizeLessThanOrEqualTo(360);
        assertThat(citation(hit, first).getBody()).containsEntry("text", original)
            .containsEntry("startOffset", 0).containsEntry("endOffset", 1006);
    }

    @Test void invalidQueryTypeLimitAndScopeAreExplicitErrors() {
        for (String q : List.of("", " ", "x".repeat(201), "!!!"))
            assertThat(search(first, q, "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String extra : List.of("&limit=0", "&limit=21", "&docType=JAVA"))
            assertThat(search(first, "valid", extra).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(search(new Scope("NAME", first.id()), "valid", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/knowledge/search?query=valid", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/knowledge/search?scopeKind=PROJECT&scopeId=arbitrary-name&query=valid", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var headers = new HttpHeaders(); headers.setOrigin("https://evil.example");
        assertThat(http.exchange("/api/knowledge/search?" + scope(first) + "&query=valid", HttpMethod.GET, new HttpEntity<>(headers), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(search(first, "valid", "").getHeaders().getCacheControl()).contains("no-store");
    }

    @Test void boundedScanReportsCoverageAndDoesNotPretendResultsAreComplete() {
        // Several individually bounded sources can exceed a single search's 2000-chunk budget.
        for (int source = 0; source < 3; source++) {
            var documents = new ArrayList<Document>();
            int count = source == 2 ? 6 : 7;
            for (int i = 0; i < count; i++)
                documents.add(doc("doc" + i + ".md", "Guide", "needle " + "x".repeat(199993)));
            if (source == 2) documents.add(doc("last.md", "Guide", "needle"));
            activate(first, null, documents);
        }
        var response = search(first, "needle", "&limit=20");
        assertThat(hits(response)).hasSize(20);
        assertThat(response.getBody()).containsEntry("scannedChunks", 2000)
            .containsEntry("availableChunks", 2001).containsEntry("truncated", true);
    }
}
