package io.github.mochiuaena.catalog;

import io.github.mochiuaena.triage.sdk.TriageJdbcObserver;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(prefix = "triage.sdk", name = "kind", havingValue = "DATABASE")
class PriceController {
    private final TriageJdbcObserver database;
    PriceController(TriageJdbcObserver database) { this.database = database; }
    @GetMapping("/api/prices/{id}")
    Map<String, Object> price(@PathVariable String id) throws SQLException {
        Map<String, Object> value = database.query(connection -> {
            try (var statement = connection.prepareStatement("SELECT price FROM products WHERE id = ?")) {
                statement.setString(1, id);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) return null;
                    BigDecimal price = result.getBigDecimal(1);
                    return Map.of("id", id, "price", price);
                }
            }
        });
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        return value;
    }
}
