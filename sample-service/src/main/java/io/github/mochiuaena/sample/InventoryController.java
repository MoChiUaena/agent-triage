package io.github.mochiuaena.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/internal/inventory")
public class InventoryController {
    private final ObservationStore store;
    public InventoryController(ObservationStore store) { this.store = store; }

    @GetMapping("/{sku}")
    public Map<String, Object> availability(@PathVariable String sku) throws InterruptedException {
        Thread.sleep(store.scenario() == ObservationStore.Scenario.DOWNSTREAM_TIMEOUT ? 600 : 15);
        return Map.of("sku", sku, "available", true);
    }
}
