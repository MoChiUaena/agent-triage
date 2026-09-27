package io.github.mochiuaena.triage.settings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=MODEL", "triage.model.api-key=", "spring.datasource.url=jdbc:h2:mem:missing-model-key;DB_CLOSE_DELAY=-1"
})
class UnconfiguredModelStartupTest {
    @Autowired TestRestTemplate http;

    @Test void theSettingsPageCanSupplyCredentialsWhenEnvironmentHasNone() {
        assertThat(http.getForEntity("/settings.html", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/config", String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(http.getForObject("/api/settings", String.class)).contains("\"mode\":\"MODEL\"", "\"providers\":[]");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Triage-Settings", "1");
        var input = Map.of("displayName", "本地服务", "protocol", "OPENAI_COMPATIBLE", "baseUrl", "http://127.0.0.1:9",
            "model", "test-local", "apiKey", "test-only-key", "temperature", 0.0,
            "timeoutSeconds", 20, "maxRounds", 4, "maxTokens", 1600, "version", 0);
        String provider = http.exchange("/api/settings/providers", HttpMethod.POST, new HttpEntity<>(input, headers), String.class).getBody();
        String id = provider.replaceAll("(?s).*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(id).isNotEqualTo(provider);
        assertThat(provider).doesNotContain("test-only-key");
        assertThat(http.exchange("/api/settings/selection", HttpMethod.PUT,
            new HttpEntity<>(Map.of("mode", "MODEL", "providerId", id), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/api/config", String.class)).contains("test-local").doesNotContain("test-only-key");
    }
}
