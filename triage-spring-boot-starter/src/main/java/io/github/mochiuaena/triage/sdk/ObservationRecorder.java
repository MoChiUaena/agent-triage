package io.github.mochiuaena.triage.sdk;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** In-memory projection. No request URLs, headers, SQL text, bodies or exception messages are retained. */
public final class ObservationRecorder {
    private static final int QUERY_GRACE_SECONDS = 120;
    record Error(Instant timestamp, String traceId, String level, String message) {}
    record EndpointError(Instant timestamp, String traceId, String level, String message,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                         FailureLocations.Location failureLocation) {}
    record DatabaseError(Instant timestamp, String traceId, String level, String message, String code) {}
    record HttpSample(Instant timestamp, double requestMs, double downstreamMs, boolean timeout, Error error, MvcEndpoint endpoint,
                      FailureLocations.Location failureLocation) {}
    record DatabaseSample(Instant timestamp, double requestMs, double acquisitionMs, double queryMs, String code,
                          DatabaseError error) {}
    record PoolSample(Instant timestamp, int active, int pending) {}
    record HttpWindow(int schemaVersion, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                      int requestCount, int timeoutCount, long recordedRequestCount, double requestP95Ms,
                      double downstreamP95Ms, double downstreamTimeoutRate, Double baselineRequestP95Ms,
                      List<Error> errors, boolean synthetic) {}
    record PoolWindow(int maximumConnections, int peakActiveConnections, int peakPendingThreads, int poolSamples,
                      int exhaustedSamples, int acquisitionTimeoutCount, int acquisitionErrorCount, int queryCount,
                      int queryErrorCount, double acquisitionP95Ms, double queryP95Ms) {}
    record DatabaseWindow(int schemaVersion, String kind, String service, String database, Instant windowStart,
                          Instant windowEnd, int requestCount, long recordedRequestCount, double requestP95Ms,
                          Double baselineRequestP95Ms, PoolWindow databasePool, List<DatabaseError> errors, boolean synthetic) {}
    record EndpointSummary(MvcEndpoint endpoint, int requestCount, int timeoutCount, double requestP95Ms, double downstreamP95Ms) {}
    record EndpointWindow(int schemaVersion, String kind, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                          int requestCount, int timeoutCount, long recordedRequestCount, double requestP95Ms, double downstreamP95Ms,
                          double downstreamTimeoutRate, Double baselineRequestP95Ms, List<EndpointError> errors, boolean synthetic,
                          MvcEndpoint endpoint, List<EndpointSummary> endpoints, int unattributedRequestCount, int otherEndpointRequestCount) {}
    private final TriageObservationProperties properties;
    private final Clock clock;
    private final Deque<HttpSample> http = new ArrayDeque<>();
    private final Deque<DatabaseSample> database = new ArrayDeque<>();
    private final Deque<PoolSample> pools = new ArrayDeque<>();
    private Instant droppedThrough;
    private long recorded;
    private int maximumConnections;

    ObservationRecorder(TriageObservationProperties properties) { this(properties, Clock.systemUTC()); }
    ObservationRecorder(TriageObservationProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    Instant now() { return clock.instant(); }
    static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    synchronized void recordHttp(double requestMs, double downstreamMs, boolean timeout, boolean failed, String trace) {
        recordHttp(requestMs, downstreamMs, timeout, failed, trace, null);
    }
    synchronized void recordHttp(double requestMs, double downstreamMs, boolean timeout, boolean failed, String trace, MvcEndpoint endpoint) {
        recordHttp(requestMs, downstreamMs, timeout, failed, trace, endpoint, null);
    }
    synchronized void recordHttp(double requestMs, double downstreamMs, boolean timeout, boolean failed, String trace, MvcEndpoint endpoint,
                                 FailureLocations.Location location) {
        Instant time = now();
        Error error = timeout ? new Error(time, trace, "ERROR", properties.getDownstreamId() + " request timeout")
            : failed ? new Error(time, trace, "ERROR", "HTTP request failed") : null;
        http.addLast(new HttpSample(time, requestMs, downstreamMs, timeout, error, endpoint, location)); recorded++;
        trim(http, HttpSample::timestamp, properties.getCapacity());
    }
    synchronized void recordDatabase(double requestMs, double acquisitionMs, double queryMs, String code, String trace) {
        Instant time = now();
        DatabaseError error = code == null ? null : new DatabaseError(time, trace, "ERROR", switch (code) {
            case "DB_CONNECTION_ACQUIRE_TIMEOUT" -> "Timed out acquiring a database connection";
            case "DB_CONNECTION_ACQUIRE_FAILED" -> "Failed acquiring a database connection";
            default -> "Database query failed after acquiring a connection";
        }, code);
        database.addLast(new DatabaseSample(time, requestMs, acquisitionMs, queryMs, code, error)); recorded++;
        trim(database, DatabaseSample::timestamp, properties.getCapacity());
    }
    synchronized void pool(int maximum, int active, int pending) {
        maximumConnections = maximum;
        pools.addLast(new PoolSample(now(), active, pending));
        trim(pools, PoolSample::timestamp, (properties.getMaxWindowMinutes() * 60 + QUERY_GRACE_SECONDS) * 20 + 20);
    }
    synchronized void configurePool(int maximum) { maximumConnections = maximum; }
    private <T> void trim(Deque<T> values, java.util.function.Function<T, Instant> timestamp, int capacity) {
        Instant cutoff = now().minusSeconds(properties.getMaxWindowMinutes() * 60L + QUERY_GRACE_SECONDS);
        values.removeIf(value -> timestamp.apply(value).isBefore(cutoff));
        while (values.size() > capacity) {
            Instant dropped = timestamp.apply(values.removeFirst());
            if (droppedThrough == null || dropped.isAfter(droppedThrough)) droppedThrough = dropped;
        }
    }
    public synchronized Object snapshot(int minutes, Instant end) {
        Instant start = windowStart(minutes, end);
        if (properties.getKind() == TriageObservationProperties.Kind.DATABASE) return databaseWindow(start, end);
        var matching = http.stream().filter(v -> within(v.timestamp(), start, end)).toList();
        int timeouts = (int) matching.stream().filter(HttpSample::timeout).count();
        return new HttpWindow(1, properties.getServiceId(), properties.getDownstreamId(), start, end, matching.size(), timeouts,
            recorded, p95(matching.stream().map(HttpSample::requestMs).toList()), p95(matching.stream().map(HttpSample::downstreamMs).toList()),
            matching.isEmpty() ? 0 : (double) timeouts / matching.size(), null, errors(matching), false);
    }
    synchronized DatabaseWindow databaseSnapshot(int minutes, Instant end) {
        return databaseWindow(windowStart(minutes, end), end);
    }
    private Instant windowStart(int minutes, Instant end) {
        if (minutes < 1 || minutes > properties.getMaxWindowMinutes() || end == null || end.isAfter(now().plusSeconds(5)))
            throw new ResponseStatusException(BAD_REQUEST, "Invalid observation window");
        Instant start = end.minusSeconds(minutes * 60L);
        if (start.isBefore(now().minusSeconds(properties.getMaxWindowMinutes() * 60L + QUERY_GRACE_SECONDS))
                || droppedThrough != null && !droppedThrough.isBefore(start))
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Observation window is no longer fully retained; reduce the window");
        return start;
    }
    public synchronized Object endpointSnapshot(int minutes, Instant end, String endpointId) {
        if (!properties.isEndpointObservations() || properties.getKind() != TriageObservationProperties.Kind.HTTP)
            throw new ResponseStatusException(NOT_FOUND, "Endpoint observations are not enabled");
        Instant start = windowStart(minutes, end);
        MvcEndpoint selected = null;
        if (endpointId != null) {
            if (!endpointId.matches("EP-[a-f0-9]{32}")) throw new ResponseStatusException(BAD_REQUEST, "Invalid endpoint identity");
            selected = http.stream().filter(value -> value.endpoint() != null && value.endpoint().id().equals(endpointId)).map(HttpSample::endpoint).findFirst()
                .orElseThrow(() -> new ResponseStatusException(CONFLICT, "The selected endpoint is no longer observed; refresh the endpoint list"));
        }
        var matching = http.stream().filter(value -> within(value.timestamp(), start, end))
            .filter(value -> endpointId == null || value.endpoint() != null && value.endpoint().id().equals(endpointId)).toList();
        var groups = new LinkedHashMap<MvcEndpoint,List<HttpSample>>();
        matching.stream().filter(value -> value.endpoint() != null).forEach(value -> groups.computeIfAbsent(value.endpoint(), ignored -> new ArrayList<>()).add(value));
        var summaries = groups.entrySet().stream().map(value -> new EndpointSummary(value.getKey(), value.getValue().size(),
            (int) value.getValue().stream().filter(HttpSample::timeout).count(), p95(value.getValue().stream().map(HttpSample::requestMs).toList()),
            p95(value.getValue().stream().map(HttpSample::downstreamMs).toList())))
            .sorted(Comparator.comparingInt(EndpointSummary::timeoutCount).reversed().thenComparing(Comparator.comparingInt(EndpointSummary::requestCount).reversed()).thenComparing(value -> value.endpoint().id()))
            .limit(8).toList();
        int unattributed = (int) matching.stream().filter(value -> value.endpoint() == null).count();
        int other = matching.size() - unattributed - summaries.stream().mapToInt(EndpointSummary::requestCount).sum();
        int timeouts = (int) matching.stream().filter(HttpSample::timeout).count();
        return new EndpointWindow(3, "HTTP_ENDPOINTS", properties.getServiceId(), properties.getDownstreamId(), start, end, matching.size(), timeouts,
            recorded, p95(matching.stream().map(HttpSample::requestMs).toList()), p95(matching.stream().map(HttpSample::downstreamMs).toList()),
            matching.isEmpty() ? 0 : (double) timeouts / matching.size(), null,
            matching.stream().filter(v -> v.error() != null).sorted(Comparator.comparing(HttpSample::timestamp).reversed()).limit(3)
                .map(v -> new EndpointError(v.error().timestamp(), v.error().traceId(), v.error().level(), v.error().message(), v.failureLocation())).toList(),
            false, selected, summaries, unattributed, other);
    }
    FailureLocations.Location requestFailure(Throwable error) { return FailureLocations.capture(error, properties, "REQUEST_EXCEPTION"); }
    private List<Error> errors(List<HttpSample> matching) {
        return matching.stream().filter(v -> v.error() != null).sorted(Comparator.comparing(HttpSample::timestamp).reversed())
            .limit(3).map(HttpSample::error).toList();
    }
    private DatabaseWindow databaseWindow(Instant start, Instant end) {
        var matching = database.stream().filter(v -> within(v.timestamp(), start, end)).toList();
        var samples = pools.stream().filter(v -> within(v.timestamp(), start, end)).toList();
        var queries = matching.stream().filter(v -> !"DB_CONNECTION_ACQUIRE_TIMEOUT".equals(v.code())
                && !"DB_CONNECTION_ACQUIRE_FAILED".equals(v.code())).toList();
        var window = new PoolWindow(maximumConnections, samples.stream().mapToInt(PoolSample::active).max().orElse(0),
            samples.stream().mapToInt(PoolSample::pending).max().orElse(0), samples.size(),
            (int) samples.stream().filter(v -> v.active() == maximumConnections && v.pending() > 0).count(),
            count(matching, "DB_CONNECTION_ACQUIRE_TIMEOUT"), count(matching, "DB_CONNECTION_ACQUIRE_FAILED"), queries.size(),
            count(matching, "SQL_QUERY_FAILED"), p95(matching.stream().map(DatabaseSample::acquisitionMs).toList()),
            p95(queries.stream().map(DatabaseSample::queryMs).toList()));
        return new DatabaseWindow(2, "DATABASE_POOL", properties.getServiceId(), properties.getDownstreamId(), start, end,
            matching.size(), recorded, p95(matching.stream().map(DatabaseSample::requestMs).toList()), null, window,
            matching.stream().filter(v -> v.error() != null).sorted(Comparator.comparing(DatabaseSample::timestamp).reversed())
                .limit(3).map(DatabaseSample::error).toList(), false);
    }
    private int count(List<DatabaseSample> values, String code) { return (int) values.stream().filter(v -> code.equals(v.code())).count(); }
    private boolean within(Instant time, Instant start, Instant end) { return !time.isBefore(start) && !time.isAfter(end); }
    private double p95(List<Double> values) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return Math.round(sorted.get((int) Math.ceil(sorted.size() * .95) - 1) * 10) / 10.0;
    }
}
