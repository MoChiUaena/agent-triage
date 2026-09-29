package io.github.mochiuaena.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "HTTP", matchIfMissing = true)
class ProductController {
    private final RestClient inventory;
    ProductController(RestClient.Builder builder, @Value("${triage.sdk.downstream-base-url}") String origin) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofMillis(300));
        inventory = builder.baseUrl(origin).requestFactory(factory).build();
    }
    @GetMapping("/api/products/{id}")
    Map<String, Object> product(@PathVariable String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,40}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product id");
        try {
            Map<?, ?> stock = inventory.get().uri("/api/inventory/{id}", id).retrieve().body(Map.class);
            return Map.of("id", id, "name", "示例商品", "available", stock != null && Boolean.TRUE.equals(stock.get("available")));
        } catch (ResourceAccessException e) { throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Inventory request did not complete"); }
    }
}
