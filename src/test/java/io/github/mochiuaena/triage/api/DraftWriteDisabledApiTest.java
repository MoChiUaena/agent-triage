package io.github.mochiuaena.triage.api;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Without a dedicated write credential every execution endpoint must refuse before touching anything. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:draft-write-disabled;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.github.token=fixture-github-key"
})
class DraftWriteDisabledApiTest {
    @Autowired TestRestTemplate http;

    static HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-Triage-Development", "1"); return h; }

    @Test void missingWriteCredentialDisablesExecutionEndpoints() {
        assertThat(http.getForEntity("/api/development/drafts/write-option", Map.class).getBody())
            .containsEntry("enabled", false);
        var preview = http.postForEntity("/api/development/drafts/" + UUID.randomUUID() + "/execution-previews",
            new HttpEntity<>(Map.of(), headers()), String.class);
        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(preview.getBody()).contains("写入凭据");
        var execution = http.exchange("/api/development/drafts/" + UUID.randomUUID() + "/executions", HttpMethod.POST,
            new HttpEntity<>(Map.of("previewId", UUID.randomUUID().toString(), "confirmed", true), headers()), String.class);
        assertThat(execution.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(execution.getBody()).contains("写入凭据");
    }
}
