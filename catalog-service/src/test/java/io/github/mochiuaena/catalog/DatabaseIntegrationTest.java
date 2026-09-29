package io.github.mochiuaena.catalog;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("database")
class DatabaseIntegrationTest {
    @Autowired TestRestTemplate http;
    @Test void dependencyRegistersTheEndpointOutsideApplicationComponentScan() {
        assertThat(http.getForEntity("/api/prices/demo", Map.class).getStatusCode().value()).isEqualTo(200);
        var result = http.getForEntity("/triage/observations?windowMinutes=5&endTime={end}", Map.class, Instant.now().toString());
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsEntry("schemaVersion", 2).containsEntry("requestCount", 1)
            .containsEntry("service", "catalog-db-service").containsEntry("synthetic", false);
        assertThat(((Map<?, ?>) result.getBody().get("databasePool")).get("queryCount")).isEqualTo(1);
    }
}
