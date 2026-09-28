package io.github.mochiuaena.database;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetAddress;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.springframework.http.HttpStatus.*;

/** Bounded fixture controls; acquired JDBC connections are always closed or expire. */
@RestController
@RequestMapping("/lab")
@ConditionalOnProperty(name = "sample.lab-enabled", havingValue = "true", matchIfMissing = true)
public class LabController {
    public record Selection(String scenario) {}
    private final HikariDataSource pool;
    private final ObservationStore observations;
    private final List<Connection> held = new ArrayList<>();
    private Instant expiresAt;
    private String scenario = "NORMAL";
    public LabController(HikariDataSource pool, ObservationStore observations) { this.pool = pool; this.observations = observations; }
    @PostMapping("/reset")
    public synchronized Map<String, Object> reset(HttpServletRequest request) {
        allowed(request); release(); observations.reset(); return Map.of("scenario", scenario);
    }
    @PostMapping("/scenario")
    public synchronized Map<String, Object> select(@RequestBody Selection selection, HttpServletRequest request) {
        allowed(request);
        if (selection.scenario() == null || !Set.of("NORMAL", "DB_POOL_EXHAUSTED", "DB_QUERY_LOCK_WAIT").contains(selection.scenario()))
            throw new ResponseStatusException(BAD_REQUEST, "Unknown database lab scenario");
        release();
        try {
            if ("DB_POOL_EXHAUSTED".equals(selection.scenario())) {
                for (int i = 0; i < pool.getMaximumPoolSize(); i++) held.add(pool.getConnection());
            } else if ("DB_QUERY_LOCK_WAIT".equals(selection.scenario())) {
                var connection = pool.getConnection(); held.add(connection); connection.setAutoCommit(false);
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT balance FROM demo_account WHERE id = 1 FOR UPDATE")) { result.next(); }
            }
            scenario = selection.scenario(); expiresAt = held.isEmpty() ? null : Instant.now().plusSeconds(10);
            observations.samplePool();
            return Map.of("scenario", scenario, "heldConnections", held.size());
        } catch (SQLException e) { release(); throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Cannot establish database lab fixture"); }
    }
    @Scheduled(fixedDelay = 100)
    public synchronized void expire() { if (expiresAt != null && Instant.now().isAfter(expiresAt)) release(); }
    @PreDestroy public synchronized void release() {
        for (Connection connection : held) try { connection.close(); } catch (SQLException ignored) { }
        held.clear(); expiresAt = null; scenario = "NORMAL";
    }
    private void allowed(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress() || !"1".equals(request.getHeader("X-Triage-Lab")))
                throw new ResponseStatusException(FORBIDDEN, "Local lab header required");
        } catch (java.net.UnknownHostException e) { throw new ResponseStatusException(FORBIDDEN, "Local lab header required"); }
        String origin = request.getHeader("Origin"), site = request.getHeader("Sec-Fetch-Site");
        if (origin != null && !origin.equals(request.getScheme() + "://" + request.getHeader("Host"))
            || site != null && !Set.of("same-origin", "none").contains(site)) throw new ResponseStatusException(FORBIDDEN, "Same-origin lab request required");
    }
}
