package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.source.SourceModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:source-api;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.settings.key-file=target/source-api-key"
})
class SourceProjectApiTest {
    @Autowired TestRestTemplate http;
    @TempDir Path root;
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
}
