package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.source.SourceModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.mochiuaena.triage.domain.TriageModel.Run;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:source-api;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.settings.key-file=target/source-api-key"
})
class SourceProjectApiTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    @BeforeEach void clearSourceFixtures() { jdbc.update("DELETE FROM source_projects"); }
    private HttpHeaders headers() { var value = new HttpHeaders(); value.setContentType(MediaType.APPLICATION_JSON); value.set("X-Triage-Source", "1"); return value; }
    @Test void registeredDirectoryHasVerifiedReferencesAndChangesRequireReindexing() throws Exception {
        Path file = root.resolve("TicketController.java"); Files.writeString(file, "class TicketController { Object ticket() { return client.retrieve(); } }");
        Map<String,Object> body = Map.of("name", "外部工单项目", "service", "order-service", "directory", root.toString());
        assertThat(http.postForEntity("/api/source-projects", body, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var response = http.postForEntity("/api/source-projects", new HttpEntity<>(body, headers()), View.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        View project = response.getBody(); assertThat(project.files()).isEqualTo(1); assertThat(project.modelSharing()).isFalse();
        Excerpt[] matches = http.getForObject("/api/source-projects/" + project.id() + "/search?q=ticket", Excerpt[].class);
        assertThat(matches).isNotEmpty(); assertThat(matches[0].path()).isEqualTo("TicketController.java");
        assertThat(matches[0].content()).contains("TicketController");
        Files.writeString(file, "class TicketController { Object ticket() { return changed(); } }");
        assertThat(http.getForEntity("/api/source-projects/" + project.id() + "/excerpts/" + matches[0].id(), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var reindexed = http.postForEntity("/api/source-projects/" + project.id() + "/reindex", new HttpEntity<>(Map.of(), headers()), View.class);
        assertThat(reindexed.getBody().revision()).isEqualTo(2);
        assertThat(reindexed.getBody().modelSharing()).isFalse();
        var sharing = Map.of("revision", 2, "enabled", true, "providerId", UUID.randomUUID(), "providerVersion", 1, "selection", "invalid-private-scope");
        var blocked = http.postForEntity("/api/source-projects/" + project.id() + "/sharing", new HttpEntity<>(sharing, headers()), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).doesNotContain("invalid-private-scope", root.toString());
        assertThat(http.getForEntity("/api/source-projects/" + project.id() + "/excerpts/../../data", String.class).getStatusCode().value()).isNotEqualTo(200);
    }
    private View create() throws Exception {
        Files.writeString(root.resolve("TicketController.java"), "class TicketController { Object ticket() { return client.retrieve(); } }");
        return http.postForObject("/api/source-projects", new HttpEntity<>(Map.of("name", "工单项目", "service", "order-service", "directory", root.toString()), headers()), View.class);
    }
    private ResponseEntity<View> update(View value, String directory, String binding, long revision) {
        var body = new HashMap<String,Object>(Map.of("name", "改名后的项目", "directory", directory, "revision", revision));
        body.put("service", binding);
        return http.exchange("/api/source-projects/" + value.id(), HttpMethod.PUT, new HttpEntity<>(body, headers()), View.class);
    }
    @Test void movesDirectoryAndUnbindsOrRebindsWithoutLeavingOldReferences() throws Exception {
        View original = create();
        Excerpt first = http.getForObject("/api/source-projects/" + original.id() + "/search?q=ticket", Excerpt[].class)[0];
        var stale = http.exchange("/api/source-projects/" + original.id(), HttpMethod.PUT,
            new HttpEntity<>(Map.of("name", "新项目", "service", "order-service", "directory", root.toString(), "revision", 99), headers()), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Path replacement = Files.createDirectory(root.resolve("replacement"));
        Files.writeString(replacement.resolve("Replacement.java"), "class Replacement { void process() {} }");
        var updated = update(original, replacement.toString(), "order-service", original.revision());
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().id()).isEqualTo(original.id());
        assertThat(updated.getBody().revision()).isEqualTo(2);
        assertThat(updated.getBody().modelSharing()).isFalse();
        assertThat(http.getForEntity("/api/source-projects/" + original.id() + "/excerpts/" + first.id(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        View unbound = update(updated.getBody(), replacement.toString(), null, 2).getBody();
        assertThat(unbound.service()).isNull(); assertThat(unbound.revision()).isEqualTo(3);
        assertThat(http.getForObject("/api/source-projects/" + original.id() + "/search?q=process", Excerpt[].class)).isNotEmpty();
        assertThat(http.getForObject("/api/config", Map.class).get("sourceProject")).isEqualTo(Map.of("available", false));
        View rebound = update(unbound, replacement.toString(), "order-service", 3).getBody();
        assertThat(rebound.service()).isEqualTo("order-service");
        assertThat(rebound.revision()).isEqualTo(4);
        var invalid = http.exchange("/api/source-projects/" + original.id(), HttpMethod.PUT,
            new HttpEntity<>(Map.of("name", "新项目", "service", "order-service", "directory", root.resolve("missing").toString(), "revision", 4), headers()), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).doesNotContain(root.toString());
        assertThat(http.getForObject("/api/source-projects", View[].class)[0].revision()).isEqualTo(4);
    }
    @Test void confirmedDeletionRemovesOnlyTheIndexAndKeepsSourceAndHistory() throws Exception {
        View project = create();
        var run = http.postForObject("/api/runs", new HttpEntity<>(Map.of("question", "订单请求慢，检查 ticket 方法", "service", "order-service", "windowMinutes", 5,
            "scenario", "DOWNSTREAM_TIMEOUT", "includeSource", true), headers()), Run.class);
        await().atMost(Duration.ofSeconds(5)).until(() -> http.getForObject("/api/runs/" + run.id(), Run.class).status().terminal());
        Run saved = http.getForObject("/api/runs/" + run.id(), Run.class);
        assertThat(saved.sourceAnalysis().excerpts()).isNotEmpty();
        String endpoint = "/api/source-projects/" + project.id();
        var confirmation = Map.of("confirmId", project.id(), "revision", project.revision());
        assertThat(http.exchange(endpoint, HttpMethod.DELETE, new HttpEntity<>(confirmation), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange(endpoint, HttpMethod.DELETE, new HttpEntity<>(Map.of("confirmId", UUID.randomUUID(), "revision", 1), headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.exchange(endpoint, HttpMethod.DELETE, new HttpEntity<>(Map.of("confirmId", project.id(), "revision", 2), headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange(endpoint, HttpMethod.DELETE, new HttpEntity<>(confirmation, headers()), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForObject("/api/source-projects", View[].class)).isEmpty();
        assertThat(Files.readString(root.resolve("TicketController.java"))).contains("client.retrieve");
        assertThat(http.getForObject("/api/runs/" + run.id(), Run.class)).isEqualTo(saved);
        assertThat(http.getForEntity(endpoint + "/search?q=ticket", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
