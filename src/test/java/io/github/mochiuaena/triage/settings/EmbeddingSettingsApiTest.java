package io.github.mochiuaena.triage.settings;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.settings.EmbeddingModels.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:search-models;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.settings.key-file=target/search-models-test-key"
})
class EmbeddingSettingsApiTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @AfterEach void reset() throws Exception {
        jdbc.update("DELETE FROM search_model_providers");
        Files.deleteIfExists(Path.of("target/search-models-test-key"));
    }

    private Map<String, Object> input(String key, long version, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("displayName", "向量服务", "baseUrl", baseUrl,
            "model", "text-embedding-v4", "timeoutSeconds", 8, "version", version));
        if (key != null) body.put("apiKey", key);
        return body;
    }

    private <T> ResponseEntity<T> mutate(HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Settings", "1");
        return http.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        var response = http.getForEntity("/api/settings/search-models", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    @Test void savesEncryptedConfigurationsPerKindWithoutReturningTheSecret() {
        var embedding = mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            input("embedding-secret", 0, "https://embedding.example/v1"), View.class);
        assertThat(embedding.getStatusCode()).isEqualTo(HttpStatus.OK);
        var created = embedding.getBody();
        assertThat(created.keyConfigured()).isTrue();
        assertThat(created.kind()).isEqualTo("EMBEDDING");
        assertThat(created.baseUrl()).isEqualTo("https://embedding.example/v1");
        assertThat(created.version()).isEqualTo(1);

        var rerank = mutate(HttpMethod.PUT, "/api/settings/search-models/rerank",
            input("rerank-secret", 0, "https://rerank.example"), View.class);
        assertThat(rerank.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rerank.getBody().kind()).isEqualTo("RERANK");

        String raw = http.getForObject("/api/settings/search-models", String.class);
        assertThat(raw).contains("text-embedding-v4").doesNotContain("embedding-secret", "rerank-secret", "encryptedKey");
        assertThat(state().get("embedding")).isNotNull();
        assertThat(state().get("rerank")).isNotNull();

        String encrypted = jdbc.queryForObject("SELECT encrypted_key FROM search_model_providers WHERE kind='EMBEDDING'", String.class);
        assertThat(encrypted).startsWith("v1:").doesNotContain("embedding-secret");
        assertThat(new CredentialCipher("target/search-models-test-key").decrypt(created.id(), encrypted)).isEqualTo("embedding-secret");
    }

    @Test void blankKeyPreservesSavedValueAndVersionConflictsAreRejected() {
        var created = mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            input("embedding-secret", 0, "https://embedding.example/v1"), View.class).getBody();
        var edit = input("", created.version(), "https://embedding.example/v1");
        edit.put("model", "text-embedding-v5");
        var updated = mutate(HttpMethod.PUT, "/api/settings/search-models/embedding", edit, View.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().version()).isEqualTo(2);
        assertThat(updated.getBody().model()).isEqualTo("text-embedding-v5");
        String encrypted = jdbc.queryForObject("SELECT encrypted_key FROM search_model_providers WHERE kind='EMBEDDING'", String.class);
        assertThat(new CredentialCipher("target/search-models-test-key").decrypt(updated.getBody().id(), encrypted))
            .isEqualTo("embedding-secret");
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding", edit, String.class).getStatusCode())
            .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void changingAddressRequiresAReplacementKey() {
        var created = mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            input("embedding-secret", 0, "https://embedding.example/v1"), View.class).getBody();
        var edit = input("", created.version(), "https://other.example/v1");
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding", edit, String.class).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT base_url FROM search_model_providers WHERE kind='EMBEDDING'", String.class))
            .isEqualTo("https://embedding.example/v1");
    }

    @Test void invalidUrlModelNameKeyAndKindAreRejected() {
        for (String url : List.of("http://embedding.example/v1", "https://embedding.example/v1?x=1",
            "https://user:pass@embedding.example/v1", "https://embedding.example/v1#f", "   ", "https://embedding.example/rerank"))
            assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
                input("k", 0, url), String.class).getStatusCode()).as(url).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            Map.of("displayName", "x", "baseUrl", "http://127.0.0.1:9/embeddings", "model", "b@d name", "apiKey", "k",
                "timeoutSeconds", 8, "version", 0), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            Map.of("displayName", "x", "baseUrl", "https://embedding.example/v1", "model", "ok-model", "apiKey", "密钥",
                "timeoutSeconds", 8, "version", 0), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            input(null, 0, "https://embedding.example/v1"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mutate(HttpMethod.PUT, "/api/settings/search-models/vector",
            input("k", 0, "https://embedding.example/v1"), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_model_providers", Long.class)).isEqualTo(0);
    }

    @Test void deleteRequiresCurrentVersionAndClearsOnlyThatKind() {
        var embedding = mutate(HttpMethod.PUT, "/api/settings/search-models/embedding",
            input("embedding-secret", 0, "https://embedding.example/v1"), View.class).getBody();
        mutate(HttpMethod.PUT, "/api/settings/search-models/rerank", input("rerank-secret", 0, "https://rerank.example"), View.class);
        assertThat(mutate(HttpMethod.DELETE, "/api/settings/search-models/embedding?version=" + (embedding.version() + 1),
            null, String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mutate(HttpMethod.DELETE, "/api/settings/search-models/embedding?version=" + embedding.version(),
            null, String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(state().get("embedding")).isNull();
        assertThat(state().get("rerank")).isNotNull();
    }

    @Test void ordinarySettingsAccessRulesApply() {
        HttpHeaders cross = new HttpHeaders(); cross.setOrigin("https://evil.example");
        assertThat(http.exchange("/api/settings/search-models", HttpMethod.GET, new HttpEntity<>(cross), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/api/settings/search-models/embedding", HttpMethod.PUT,
            new HttpEntity<>(input("k", 0, "https://embedding.example/v1"), new HttpHeaders()), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/settings/search-models", String.class).getHeaders().getCacheControl())
            .contains("no-store");
    }
}
