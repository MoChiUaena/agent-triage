package io.github.mochiuaena.catalog;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

class CheckoutControllerTest {
    @Test void inventoryTimeoutDoesNotHideSuccessfulAssignment() throws Exception {
        var slow = new AtomicBoolean(false);
        var workers = Executors.newCachedThreadPool();
        var inventory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var assignment = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        inventory.setExecutor(workers);
        assignment.setExecutor(workers);
        inventory.createContext("/api/inventory/demo", exchange -> {
            try {
                if (slow.get()) Thread.sleep(600);
                reply(exchange, "{\"available\":true}");
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* A timed-out client may already have closed the socket. */ }
        });
        assignment.createContext("/assignments/demo", exchange -> reply(exchange, "{\"assigned\":true}"));
        inventory.start(); assignment.start();
        try {
            var controller = new CheckoutController(RestClient.builder(),
                "http://127.0.0.1:" + inventory.getAddress().getPort(),
                "http://127.0.0.1:" + assignment.getAddress().getPort());
            assertThat(controller.checkout("demo")).containsEntry("inventory", "available")
                .containsEntry("assignment", "assigned");
            slow.set(true);
            assertThat(controller.checkout("demo")).containsEntry("inventory", "timeout")
                .containsEntry("assignment", "assigned");
        } finally {
            inventory.stop(0); assignment.stop(0); workers.shutdownNow(); workers.close();
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
