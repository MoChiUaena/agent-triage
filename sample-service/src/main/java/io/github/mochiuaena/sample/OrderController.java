package io.github.mochiuaena.sample;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    private final ObservationStore store;
    public OrderController(ObservationStore store) { this.store = store; }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String orderId, HttpServletRequest servletRequest) {
        String traceId = UUID.randomUUID().toString();
        ObservationStore.Scenario scenario = store.scenario();
        long started = System.nanoTime();
        long downstreamStarted = System.nanoTime();
        boolean timedOut = false;
        try {
            URI uri = URI.create("http://127.0.0.1:" + servletRequest.getLocalPort() + "/internal/inventory/sku");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(300))
                .header("X-Trace-Id", traceId).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Inventory returned " + response.statusCode());
            return ResponseEntity.ok(Map.of("orderId", orderId, "available", true, "traceId", traceId));
        } catch (HttpTimeoutException e) {
            timedOut = true;
            log.error("inventory read timeout traceId={} orderId={} timeoutMs=300", traceId, orderId);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Inventory read timed out", "traceId", traceId));
        } catch (IOException e) {
            log.error("inventory request failed traceId={} orderId={}", traceId, orderId);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Inventory request failed", "traceId", traceId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Request interrupted", "traceId", traceId));
        } finally {
            double orderMs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 1000.0;
            double downstreamMs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - downstreamStarted) / 1000.0;
            store.record(new ObservationStore.RequestSample(Instant.now(), scenario, orderMs, downstreamMs, timedOut, traceId));
        }
    }
}
