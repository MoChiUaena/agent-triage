package io.github.mochiuaena.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Component
public class ObservationStore {
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message, String code) {}
    public record RequestSample(Instant timestamp, double requestMs, double acquisitionMs, double queryMs,
                                boolean acquisitionTimeout, boolean acquisitionError, boolean queryError, ErrorEntry error) {}
    private record PoolSample(Instant timestamp, int active, int pending) {}
    public record PoolWindow(int maximumConnections, int peakActiveConnections, int peakPendingThreads,
                             int poolSamples, int exhaustedSamples, int acquisitionTimeoutCount, int acquisitionErrorCount, int queryCount, int queryErrorCount,
                             double acquisitionP95Ms, double queryP95Ms) {}
    public record Snapshot(int schemaVersion, String kind, String service, String database, Instant windowStart, Instant windowEnd,
                           int requestCount, long recordedRequestCount, double requestP95Ms, Double baselineRequestP95Ms,
                           PoolWindow databasePool, List<ErrorEntry> errors, boolean synthetic) {}
    private final Deque<RequestSample> requests = new ArrayDeque<>();
    private final Deque<PoolSample> pools = new ArrayDeque<>();
    private final HikariDataSource pool;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final Path journal;
    private final String serviceId, databaseId;
    private final Instant startedAt = Instant.now();
    private long recorded;
    private Instant droppedThrough;

    public ObservationStore(HikariDataSource pool, MeterRegistry metrics, ObjectMapper json,
                            @Value("${sample.service-id:account-service}") String serviceId,
                            @Value("${sample.database-id:accounts-db}") String databaseId,
                            @Value("${sample.error-log-file:./data/database-errors.jsonl}") String file) {
        if (!serviceId.matches("[a-z][a-z0-9-]{0,63}") || !databaseId.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid database sample identity");
        this.pool = pool; this.metrics = metrics; this.json = json; this.serviceId = serviceId; this.databaseId = databaseId;
        journal = Path.of(file).toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelay = 50)
    public synchronized void samplePool() {
        var bean = pool.getHikariPoolMXBean();
        if (bean == null) return;
        pools.addLast(new PoolSample(Instant.now(), bean.getActiveConnections(), bean.getThreadsAwaitingConnection()));
        while (pools.size() > 72_000) dropped(pools.removeFirst().timestamp());
    }
    public synchronized void record(RequestSample value) {
        requests.addLast(value); recorded++;
        while (requests.size() > 10_000) dropped(requests.removeFirst().timestamp());
        metrics.counter("sample.database.requests", "outcome", value.acquisitionTimeout() ? "acquisition_timeout" : value.acquisitionError() ? "acquisition_error" : value.queryError() ? "query_error" : "ok").increment();
        if (value.error() != null) append(value.error());
    }
    public synchronized void reset() {
        requests.clear(); pools.clear();
        droppedThrough = null;
        try { Files.createDirectories(journal.getParent()); Files.writeString(journal, "", StandardCharsets.UTF_8); }
        catch (IOException e) { throw new UncheckedIOException("Cannot reset database error journal", e); }
    }
    private void dropped(Instant time) {
        if (droppedThrough == null || time.isAfter(droppedThrough)) droppedThrough = time;
    }
    private void append(ErrorEntry value) {
        try {
            Files.createDirectories(journal.getParent());
            if (Files.exists(journal) && Files.size(journal) >= 2_000_000) Files.writeString(journal, "", StandardCharsets.UTF_8);
            Files.writeString(journal, json.writeValueAsString(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException("Cannot append database error journal", e); }
    }
    private List<ErrorEntry> errors(Instant start, Instant end) {
        if (!Files.exists(journal)) return List.of();
        try {
            var lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
            var values = new ArrayList<ErrorEntry>();
            for (int i = lines.size() - 1; i >= 0 && values.size() < 3; i--) {
                if (lines.get(i).isBlank()) continue;
                var entry = json.readValue(lines.get(i), ErrorEntry.class);
                if (!entry.timestamp().isBefore(start) && !entry.timestamp().isBefore(startedAt) && !entry.timestamp().isAfter(end)) values.add(entry);
            }
            return List.copyOf(values);
        } catch (IOException e) { throw new UncheckedIOException("Cannot read database error journal", e); }
    }
    public synchronized Snapshot snapshot(int minutes, Instant end) {
        if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("Window must be 1..60 minutes");
        Instant start = end.minusSeconds(minutes * 60L);
        if (droppedThrough != null && !droppedThrough.isBefore(start))
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Observation window is no longer fully retained; reduce the window");
        var samples = requests.stream().filter(v -> !v.timestamp().isBefore(start) && !v.timestamp().isAfter(end)).toList();
        var poolSamples = pools.stream().filter(v -> !v.timestamp().isBefore(start) && !v.timestamp().isAfter(end)).toList();
        var normal = samples.stream().filter(v -> !v.acquisitionTimeout() && !v.acquisitionError() && !v.queryError()).toList();
        var queries = samples.stream().filter(v -> !v.acquisitionTimeout() && !v.acquisitionError()).toList();
        int maximum = pool.getMaximumPoolSize();
        var window = new PoolWindow(maximum, poolSamples.stream().mapToInt(PoolSample::active).max().orElse(0),
            poolSamples.stream().mapToInt(PoolSample::pending).max().orElse(0), poolSamples.size(),
            (int) poolSamples.stream().filter(v -> v.active() == maximum && v.pending() > 0).count(),
            (int) samples.stream().filter(RequestSample::acquisitionTimeout).count(), (int) samples.stream().filter(RequestSample::acquisitionError).count(),
            queries.size(), (int) samples.stream().filter(RequestSample::queryError).count(),
            p95(samples.stream().map(RequestSample::acquisitionMs).toList()), p95(queries.stream().map(RequestSample::queryMs).toList()));
        return new Snapshot(2, "DATABASE_POOL", serviceId, databaseId, start, end, samples.size(), recorded,
            p95(samples.stream().map(RequestSample::requestMs).toList()), normal.isEmpty() ? null : p95(normal.stream().map(RequestSample::requestMs).toList()),
            window, errors(start, end), false);
    }
    private static double p95(List<Double> values) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return Math.round(sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) * 10) / 10.0;
    }
}
