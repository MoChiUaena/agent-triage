package io.github.mochiuaena.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InventoryHttpTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired MeterRegistry metrics;

    @Test void servesActualRequestsInBothScenariosAndRecordsTimers() {
        String base = "http://127.0.0.1:" + port;
        http.postForEntity(base + "/lab/reset", null, Map.class);
        var normal = http.getForEntity(base + "/api/inventory/sku-1", Map.class);
        assertThat(normal.getStatusCode().value()).isEqualTo(200);
        http.postForEntity(base + "/lab/scenario", Map.of("scenario", "DOWNSTREAM_TIMEOUT"), Map.class);
        long started = System.nanoTime();
        var slow = http.getForEntity(base + "/api/inventory/sku-2", Map.class);
        double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
        assertThat(slow.getStatusCode().value()).isEqualTo(200);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(500);
        assertThat(metrics.get("sample.inventory.duration").timers()).extracting(Timer::count)
            .containsExactlyInAnyOrder(1L, 1L);
    }
}
