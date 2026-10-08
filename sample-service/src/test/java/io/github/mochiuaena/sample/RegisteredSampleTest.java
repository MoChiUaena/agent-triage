package io.github.mochiuaena.sample;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "sample.service-id=checkout-service", "sample.downstream-service=stock-service", "sample.lab-enabled=false",
    "sample.error-log-file=target/test-checkout-errors.jsonl"
})
class RegisteredSampleTest {
    private static HttpServer downstream;
    @Autowired TestRestTemplate http;
    @Autowired InventoryClient inventory;
    @DynamicPropertySource static void downstream(DynamicPropertyRegistry properties) throws Exception {
        downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/api/inventory/sku", exchange -> { exchange.sendResponseHeaders(200, -1); exchange.close(); });
        downstream.start();
        properties.add("sample.inventory.base-url", () -> "http://127.0.0.1:" + downstream.getAddress().getPort());
    }
    @AfterAll static void stop() { downstream.stop(0); }

    @Test void exportsItsConfiguredIdentityAndRealRequestsWithoutLabEndpoints() {
        // Warm the client's first connection without recording an application request.
        await().atMost(Duration.ofSeconds(5)).ignoreException(java.net.http.HttpTimeoutException.class)
            .untilAsserted(() -> inventory.availability("test-warmup"));
        assertThat(http.getForEntity("/api/requests/test", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Instant end = Instant.now();
        var value = http.getForObject("/triage/observations?windowMinutes=5&endTime={end}", ObservationsController.Observations.class, end);
        assertThat(value.schemaVersion()).isEqualTo(1);
        assertThat(value.service()).isEqualTo("checkout-service");
        assertThat(value.downstreamService()).isEqualTo("stock-service");
        assertThat(value.requestCount()).isEqualTo(1);
        assertThat(value.recordedRequestCount()).isEqualTo(1);
        assertThat(value.synthetic()).isFalse();
        assertThat(value.windowStart()).isEqualTo(end.minusSeconds(300));
        assertThat(value.windowEnd()).isEqualTo(end);
        assertThat(http.getForEntity("/lab/scenario", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/lab/reset", "{}", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/triage/observations?windowMinutes=61&endTime={end}", String.class, end).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
