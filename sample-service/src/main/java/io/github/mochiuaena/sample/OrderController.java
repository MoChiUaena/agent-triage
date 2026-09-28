package io.github.mochiuaena.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping({"/api/orders", "/api/requests"})
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final ObservationStore store;
    private final InventoryClient inventory;
    private final ErrorJournal errors;
    public OrderController(ObservationStore store, InventoryClient inventory, ErrorJournal errors) {
        this.store = store; this.inventory = inventory; this.errors = errors;
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String orderId) {
        String traceId = UUID.randomUUID().toString();
        ObservationStore.Scenario scenario = store.scenario();
        long started = System.nanoTime();
        long downstreamStarted = System.nanoTime();
        String outcome = "ok";
        try {
            inventory.availability(traceId);
            return ResponseEntity.ok(Map.of("orderId", orderId, "available", true, "traceId", traceId));
        } catch (HttpTimeoutException e) {
            outcome = "timeout";
            log.error("inventory request timeout traceId={} orderId={} timeoutMs=300", traceId, orderId);
            errors.append(new ObservationStore.ErrorEntry(Instant.now(), traceId, "ERROR",
                "GET " + store.downstreamService() + " /api/inventory/sku: request timeout after 300ms"));
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("error", "Inventory request timed out", "traceId", traceId));
        } catch (IOException e) {
            outcome = "error";
            log.error("inventory request failed traceId={} orderId={}", traceId, orderId);
            errors.append(new ObservationStore.ErrorEntry(Instant.now(), traceId, "ERROR",
                "GET " + store.downstreamService() + " /api/inventory/sku: request failed"));
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Inventory request failed", "traceId", traceId));
        } catch (InterruptedException e) {
            outcome = "error";
            Thread.currentThread().interrupt();
            errors.append(new ObservationStore.ErrorEntry(Instant.now(), traceId, "ERROR",
                "GET " + store.downstreamService() + " /api/inventory/sku: request interrupted"));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Request interrupted", "traceId", traceId));
        } finally {
            double orderMs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 1000.0;
            double downstreamMs = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - downstreamStarted) / 1000.0;
            store.record(new ObservationStore.RequestSample(Instant.now(), scenario, orderMs, downstreamMs, outcome, traceId));
        }
    }
}
