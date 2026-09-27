package io.github.mochiuaena.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveLabTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    @Test void recordsActualInventoryCallsAndTimeouts() {
        String base = "http://127.0.0.1:" + port;
        http.getForEntity(base + "/api/orders/warmup", Map.class);
        http.postForEntity(base + "/lab/reset", null, Map.class);
        for (int i = 0; i < 3; i++) {
            assertThat(http.getForEntity(base + "/api/orders/normal-" + i, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ObservationStore.Snapshot normal = http.getForObject(base + "/lab/observations?windowMinutes=1", ObservationStore.Snapshot.class);
        assertThat(normal.requestCount()).isEqualTo(3);
        assertThat(normal.normalCount()).isEqualTo(3);
        assertThat(normal.orderP95Ms()).isPositive();
        assertThat(normal.downstreamTimeoutRate()).isZero();
        assertThat(normal.synthetic()).isFalse();

        http.postForEntity(base + "/lab/scenario", Map.of("scenario", "DOWNSTREAM_TIMEOUT"), Map.class);
        for (int i = 0; i < 2; i++) {
            assertThat(http.getForEntity(base + "/api/orders/slow-" + i, Map.class).getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        }
        ObservationStore.Snapshot slow = http.getForObject(base + "/lab/observations?windowMinutes=1", ObservationStore.Snapshot.class);
        assertThat(slow.requestCount()).isEqualTo(5);
        assertThat(slow.timeoutCount()).isEqualTo(2);
        assertThat(slow.downstreamTimeoutRate()).isEqualTo(0.4);
        assertThat(slow.errors()).hasSize(2).allSatisfy(entry -> assertThat(entry.traceId()).isNotBlank());
        assertThat(slow.baselineOrderP95Ms()).isPositive();
    }
}
