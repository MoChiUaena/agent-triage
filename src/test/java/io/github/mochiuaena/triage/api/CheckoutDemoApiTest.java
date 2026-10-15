package io.github.mochiuaena.triage.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "triage.observation.source=LIVE", "triage.demo.checkout-enabled=true",
    "spring.datasource.url=jdbc:h2:mem:checkout-demo;DB_CLOSE_DELAY=-1",
    "triage.settings.key-file=target/checkout-demo-test-key"
})
class CheckoutDemoApiTest {
    private static final AtomicReference<String> scenario = new AtomicReference<>("NORMAL");
    private static final AtomicReference<String> seenAtCheckout = new AtomicReference<>();
    private static final AtomicInteger checkoutCalls = new AtomicInteger();
    private static final HttpServer order = start(false);
    private static final HttpServer catalog = start(true);
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void services(DynamicPropertyRegistry values) {
        values.add("triage.services[0].id", () -> "order-service");
        values.add("triage.services[0].name", () -> "订单服务");
        values.add("triage.services[0].protocol", () -> "LAB");
        values.add("triage.services[0].downstream-id", () -> "inventory-service");
        values.add("triage.services[0].base-url", () -> origin(order));
        values.add("triage.services[0].lab-enabled", () -> "true");
        values.add("triage.services[1].id", () -> "catalog-service");
        values.add("triage.services[1].name", () -> "商品服务");
        values.add("triage.services[1].protocol", () -> "OBSERVATIONS_V5");
        values.add("triage.services[1].base-url", () -> origin(catalog));
        values.add("triage.services[1].downstreams[0].id", () -> "inventory-service");
        values.add("triage.services[1].downstreams[1].id", () -> "assignment-service");
    }

    @Test void checkoutTrafficRequiresLocalIntentAndRestoresTheSharedInventoryFixture() {
        var body = Map.of("service", "catalog-service", "scenario", "DOWNSTREAM_TIMEOUT", "count", 2);
        assertThat(http.postForEntity("/api/live-lab/traffic", body, String.class).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(checkoutCalls).hasValue(0);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Triage-Lab", "1");
        var response = http.postForEntity("/api/live-lab/traffic", new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("generatedRequests", 2).containsEntry("scenario", "OBSERVED")
            .containsEntry("inventoryTimeoutCount", 2);
        assertThat(checkoutCalls).hasValue(2);
        assertThat(seenAtCheckout).hasValue("DOWNSTREAM_TIMEOUT");
        assertThat(scenario).hasValue("NORMAL");
        var config = http.getForEntity("/api/config?service=catalog-service", Map.class);
        assertThat(config.getBody()).containsEntry("checkoutDemoEnabled", true);
        var normal = http.postForEntity("/api/live-lab/traffic", new HttpEntity<>(Map.of("service", "catalog-service",
            "scenario", "NORMAL", "count", 1), headers), Map.class);
        assertThat(normal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(normal.getBody()).containsEntry("inventoryTimeoutCount", 0);
        assertThat(seenAtCheckout).hasValue("NORMAL");
        assertThat(scenario).hasValue("NORMAL");
        assertThat(http.getForEntity("/api/config?service=order-service", Map.class).getBody())
            .containsEntry("checkoutDemoEnabled", false);
    }

    private static HttpServer start(boolean isCatalog) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (isCatalog && "/api/checkouts/demo".equals(path)) {
                    checkoutCalls.incrementAndGet(); seenAtCheckout.set(scenario.get());
                    reply(exchange, "{\"inventory\":\"" + ("DOWNSTREAM_TIMEOUT".equals(scenario.get()) ? "timeout" : "available")
                        + "\",\"assignment\":\"assigned\"}");
                } else if (!isCatalog && "/lab/reset".equals(path)) {
                    scenario.set("NORMAL"); reply(exchange, "{}");
                } else if (!isCatalog && "/lab/scenario".equals(path)) {
                    String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    scenario.set(input.contains("DOWNSTREAM_TIMEOUT") ? "DOWNSTREAM_TIMEOUT" : "NORMAL");
                    reply(exchange, "{}");
                } else { exchange.sendResponseHeaders(404, -1); exchange.close(); }
            });
            server.start(); return server;
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }

    private static String origin(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    @AfterAll static void stop() { order.stop(0); catalog.stop(0); }
}
