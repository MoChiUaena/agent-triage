package io.github.mochiuaena.inventory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    private final InventoryState state;
    private final MeterRegistry metrics;
    public InventoryController(InventoryState state, MeterRegistry metrics) {
        this.state = state; this.metrics = metrics;
    }

    @GetMapping("/{sku}")
    public Map<String, Object> availability(@PathVariable String sku,
                                            @RequestHeader(value = "X-Trace-Id", defaultValue = "") String traceId) {
        InventoryState.Scenario scenario = state.scenario();
        long started = System.nanoTime();
        try {
            Thread.sleep(scenario == InventoryState.Scenario.DOWNSTREAM_TIMEOUT ? 600 : 15);
            return Map.of("sku", sku, "available", true, "traceId", traceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Inventory request interrupted");
        } finally {
            Timer.builder("sample.inventory.duration").tag("scenario", scenario.name())
                .register(metrics).record(Duration.ofNanos(System.nanoTime() - started));
        }
    }
}
