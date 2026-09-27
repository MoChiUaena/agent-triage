package io.github.mochiuaena.sample;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Bounded in-memory projection of requests that this service actually handled. */
@Component
public class ObservationStore {
    public enum Scenario { NORMAL, DOWNSTREAM_TIMEOUT }
    public record RequestSample(Instant timestamp, Scenario scenario, double orderMs, double downstreamMs,
                                boolean timedOut, String traceId) {}
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message) {}
    public record Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                           int requestCount, int normalCount, int timeoutCount, double orderP95Ms,
                           double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms,
                           List<ErrorEntry> errors, boolean synthetic) {}

    private static final int MAX_SAMPLES = 10_000;
    private final Deque<RequestSample> samples = new ArrayDeque<>();
    private Scenario scenario = Scenario.NORMAL;

    public synchronized Scenario scenario() { return scenario; }
    public synchronized void scenario(Scenario value) { scenario = value; }

    public synchronized void reset() {
        samples.clear();
        scenario = Scenario.NORMAL;
    }

    public synchronized void record(RequestSample sample) {
        samples.addLast(sample);
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
    }

    public synchronized Snapshot snapshot(int windowMinutes, Instant end) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new IllegalArgumentException("windowMinutes must be 1..60");
        Instant start = end.minus(windowMinutes, ChronoUnit.MINUTES);
        List<RequestSample> matching = samples.stream()
            .filter(sample -> !sample.timestamp().isBefore(start) && !sample.timestamp().isAfter(end)).toList();
        List<RequestSample> normals = matching.stream().filter(sample -> sample.scenario() == Scenario.NORMAL && !sample.timedOut()).toList();
        List<RequestSample> failures = matching.stream().filter(RequestSample::timedOut).toList();
        List<ErrorEntry> errors = failures.reversed().stream().limit(3)
            .map(sample -> new ErrorEntry(sample.timestamp(), sample.traceId(), "ERROR",
                "GET /internal/inventory/sku: java.net.http.HttpTimeoutException: request timeout after 300ms"))
            .toList();
        return new Snapshot("order-service", scenario, start, end, matching.size(), normals.size(), failures.size(),
            p95(matching.stream().map(RequestSample::orderMs).toList()),
            p95(matching.stream().map(RequestSample::downstreamMs).toList()),
            matching.isEmpty() ? 0 : (double) failures.size() / matching.size(),
            normals.isEmpty() ? null : p95(normals.stream().map(RequestSample::orderMs).toList()),
            errors, false);
    }

    private static double p95(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        double value = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        return Math.round(value * 10.0) / 10.0;
    }
}
