package io.github.mochiuaena.catalog;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
class CheckoutController {
    private final RestClient inventory;
    private final RestClient assignment;

    CheckoutController(RestClient.Builder builder, @Value("${catalog.inventory-url}") String inventoryUrl,
                       @Value("${catalog.assignment-url}") String assignmentUrl) {
        var stockFactory = new SimpleClientHttpRequestFactory();
        stockFactory.setConnectTimeout(1000); stockFactory.setReadTimeout(300);
        var assignmentFactory = new SimpleClientHttpRequestFactory();
        assignmentFactory.setConnectTimeout(1000); assignmentFactory.setReadTimeout(1500);
        inventory = builder.clone().baseUrl(inventoryUrl).requestFactory(stockFactory).build();
        assignment = builder.clone().baseUrl(assignmentUrl).requestFactory(assignmentFactory).build();
    }

    @GetMapping("/api/checkouts/{id}")
    Map<String, String> checkout(@PathVariable String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,40}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String stock;
        try {
            Map<?, ?> response = inventory.get().uri("/api/inventory/{id}", id).retrieve().body(Map.class);
            stock = response != null && Boolean.TRUE.equals(response.get("available")) ? "available" : "unavailable";
        } catch (ResourceAccessException timeout) { stock = "timeout"; }
        String assigned;
        try {
            Map<?, ?> response = assignment.get().uri("/assignments/{id}", id).retrieve().body(Map.class);
            assigned = response != null && Boolean.TRUE.equals(response.get("assigned")) ? "assigned" : "unavailable";
        } catch (ResourceAccessException timeout) { assigned = "timeout"; }
        return Map.of("id", id, "inventory", stock, "assignment", assigned);
    }
}
