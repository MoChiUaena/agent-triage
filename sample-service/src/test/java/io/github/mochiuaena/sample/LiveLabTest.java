package io.github.mochiuaena.sample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "sample.error-log-file=target/test-sample-errors.jsonl")
class LiveLabTest {
    private static final AtomicReference<String> inventoryScenario = new AtomicReference<>("NORMAL");
    private static HttpServer inventoryServer;
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired MeterRegistry metrics;

    @DynamicPropertySource
    static synchronized void inventoryUrl(DynamicPropertyRegistry registry) throws IOException {
        inventoryServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventoryServer.createContext("/api/inventory/", exchange -> {
            try {
                Thread.sleep("DOWNSTREAM_TIMEOUT".equals(inventoryScenario.get()) ? 600 : 15);
                respond(exchange, "{\"available\":true}");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        inventoryServer.createContext("/lab/scenario", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                inventoryScenario.set(body.contains("DOWNSTREAM_TIMEOUT") ? "DOWNSTREAM_TIMEOUT" : "NORMAL");
            }
            respond(exchange, "{\"scenario\":\"" + inventoryScenario.get() + "\"}");
        });
        inventoryServer.createContext("/lab/reset", exchange -> {
            inventoryScenario.set("NORMAL");
            respond(exchange, "{\"scenario\":\"NORMAL\"}");
        });
        inventoryServer.setExecutor(Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "fake-inventory");
            thread.setDaemon(true);
            return thread;
        }));
        inventoryServer.start();
        registry.add("sample.inventory.base-url", () -> "http://127.0.0.1:" + inventoryServer.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var stream = exchange.getResponseBody()) { stream.write(body); }
    }

    @AfterAll static void stopInventory() { if (inventoryServer != null) inventoryServer.stop(0); }

    @Test void recordsCrossPortCallsMicrometerMetricsAndStructuredErrors() throws IOException {
        String base = "http://127.0.0.1:" + port;
        http.getForEntity(base + "/api/orders/warmup", Map.class);
        http.postForEntity(base + "/lab/reset", null, Map.class);
        for (int i = 0; i < 3; i++)
            assertThat(http.getForEntity(base + "/api/orders/normal-" + i, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ObservationStore.Snapshot normal = http.getForObject(base + "/lab/observations?windowMinutes=1", ObservationStore.Snapshot.class);
        assertThat(normal.requestCount()).isEqualTo(3);
        assertThat(normal.normalCount()).isEqualTo(3);
        assertThat(normal.orderP95Ms()).isPositive();
        assertThat(normal.downstreamTimeoutRate()).isZero();
        assertThat(normal.synthetic()).isFalse();

        http.postForEntity(base + "/lab/scenario", Map.of("scenario", "DOWNSTREAM_TIMEOUT"), Map.class);
        for (int i = 0; i < 2; i++)
            assertThat(http.getForEntity(base + "/api/orders/slow-" + i, Map.class).getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        ObservationStore.Snapshot slow = http.getForObject(base + "/lab/observations?windowMinutes=1", ObservationStore.Snapshot.class);
        assertThat(slow.requestCount()).isEqualTo(5);
        assertThat(slow.timeoutCount()).isEqualTo(2);
        assertThat(slow.downstreamTimeoutRate()).isEqualTo(0.4);
        assertThat(slow.errors()).hasSize(2).allSatisfy(entry -> assertThat(entry.traceId()).isNotBlank());
        assertThat(slow.baselineOrderP95Ms()).isPositive();
        assertThat(slow.recordedRequestCount()).isGreaterThanOrEqualTo(5);
        assertThat(metrics.find("sample.order.requests").counters()).isNotEmpty();
        assertThat(http.getForObject(base + "/actuator/metrics/sample.order.requests", String.class))
            .contains("sample.order.requests", "COUNT");
        String file = Files.readString(Path.of("target/test-sample-errors.jsonl"));
        assertThat(file).contains(slow.errors().getFirst().traceId(), "\"level\":\"ERROR\"");
    }
}
