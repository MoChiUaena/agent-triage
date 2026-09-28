package io.github.mochiuaena.database;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@RestController
public class AccountController {
    private final HikariDataSource pool;
    private final ObservationStore observations;
    public AccountController(HikariDataSource pool, ObservationStore observations) throws SQLException {
        this.pool = pool; this.observations = observations;
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS demo_account (id INT PRIMARY KEY, balance INT)");
            statement.execute("MERGE INTO demo_account KEY(id) VALUES (1, 100)");
        }
    }
    @GetMapping("/api/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> account(@PathVariable int accountId) {
        String trace = UUID.randomUUID().toString();
        long started = System.nanoTime(), queryStarted = 0;
        double acquisition = 0, query = 0;
        boolean acquired = false, timeout = false, acquisitionError = false, queryError = false;
        ObservationStore.ErrorEntry error = null;
        observations.samplePool();
        try (Connection connection = pool.getConnection()) {
            acquisition = elapsed(started); acquired = true;
            observations.samplePool(); queryStarted = System.nanoTime();
            try (var statement = connection.prepareStatement("SELECT balance FROM demo_account WHERE id = ? FOR UPDATE")) {
                statement.setInt(1, accountId);
                try (var result = statement.executeQuery()) {
                    query = elapsed(queryStarted);
                    return result.next() ? ResponseEntity.ok(Map.of("accountId", accountId, "balance", result.getInt(1), "traceId", trace))
                        : ResponseEntity.status(404).body(Map.of("error", "Sample account not found", "traceId", trace));
                }
            }
        } catch (SQLException e) {
            if (!acquired) { timeout = e instanceof SQLTransientConnectionException; acquisitionError = !timeout; acquisition = elapsed(started); }
            else { queryError = true; query = elapsed(queryStarted); }
            String code = timeout ? "DB_CONNECTION_ACQUIRE_TIMEOUT" : acquisitionError ? "DB_CONNECTION_ACQUIRE_FAILED" : "SQL_QUERY_FAILED";
            error = new ObservationStore.ErrorEntry(Instant.now(), trace, "ERROR",
                timeout ? "HikariCP connection acquisition timed out" : acquisitionError ? "Database connection acquisition failed" : "Database query failed after acquiring a connection", code);
            return ResponseEntity.status(!acquired ? 503 : 500).body(Map.of("error", code, "traceId", trace));
        } finally {
            observations.samplePool();
            Instant ended = Instant.now();
            if (error != null) error = new ObservationStore.ErrorEntry(ended, trace, error.level(), error.message(), error.code());
            observations.record(new ObservationStore.RequestSample(ended, elapsed(started), acquisition, query, timeout, acquisitionError, queryError, error));
        }
    }
    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
