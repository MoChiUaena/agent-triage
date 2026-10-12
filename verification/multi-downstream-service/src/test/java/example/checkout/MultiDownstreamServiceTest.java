package example.checkout;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiDownstreamServiceTest {
    private static HttpServer inventory;
    private static HttpServer payment;
    private static final java.util.concurrent.atomic.AtomicBoolean paymentFails = new java.util.concurrent.atomic.AtomicBoolean();
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry properties) throws Exception {
        inventory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventory.createContext("/stock", exchange -> {
            try { Thread.sleep(350); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] body = "stock".getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); } }
            catch (java.io.IOException ignored) { exchange.close(); }
        });
        payment = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        payment.createContext("/payment", exchange -> {
            boolean failed = paymentFails.get();
            byte[] body = (failed ? "failed" : "ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(failed ? 500 : 200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        inventory.start(); payment.start();
        String inventoryUrl = "http://127.0.0.1:" + inventory.getAddress().getPort();
        String paymentUrl = "http://127.0.0.1:" + payment.getAddress().getPort();
        properties.add("checkout.inventory-url", () -> inventoryUrl);
        properties.add("checkout.payment-url", () -> paymentUrl);
        properties.add("triage.sdk.enabled", () -> "true");
        properties.add("triage.sdk.service-id", () -> "checkout-service");
        properties.add("triage.sdk.endpoint-observations", () -> "true");
        properties.add("triage.sdk.downstreams[0].id", () -> "inventory-service");
        properties.add("triage.sdk.downstreams[0].base-url", () -> inventoryUrl);
        properties.add("triage.sdk.downstreams[1].id", () -> "payment-service");
        properties.add("triage.sdk.downstreams[1].base-url", () -> paymentUrl);
    }
    @AfterAll static void stop() { if (inventory != null) inventory.stop(0); if (payment != null) payment.stop(0); }

    @Test void oneRequestWithTwoRealHttpCallsKeepsTheTimeoutOnInventory() {
        paymentFails.set(false);
        assertThat(http.getForObject("/api/checkout", String.class)).isEqualTo("stock=unavailable;payment=ok");
        paymentFails.set(true);
        assertThat(http.getForObject("/api/checkout", String.class)).isEqualTo("stock=unavailable;payment=failed");
        JsonNode observed = http.getForObject(java.net.URI.create("/triage/endpoint-observations?windowMinutes=5&endTime="
            + Instant.now()), JsonNode.class);
        assertThat(observed.path("schemaVersion").asInt()).isEqualTo(5);
        assertThat(observed.path("requestCount").asInt()).isEqualTo(2);
        assertThat(observed.path("timeoutCount").asInt()).isEqualTo(2);
        assertThat(observed.at("/downstreams/0/id").asText()).isEqualTo("inventory-service");
        assertThat(observed.at("/downstreams/0/timeoutCount").asInt()).isEqualTo(2);
        assertThat(observed.at("/downstreams/1/id").asText()).isEqualTo("payment-service");
        assertThat(observed.at("/downstreams/1/timeoutCount").asInt()).isZero();
        assertThat(observed.at("/downstreams/1/responseStatuses/successful").asInt()).isEqualTo(1);
        assertThat(observed.at("/downstreams/1/responseStatuses/serverError").asInt()).isEqualTo(1);
    }
}
