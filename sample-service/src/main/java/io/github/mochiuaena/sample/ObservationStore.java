package io.github.mochiuaena.sample;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/** Bounded in-memory projection of requests that this service actually handled. */
@Component
public class ObservationStore {
    public enum Scenario { NORMAL, DOWNSTREAM_TIMEOUT }
    public record RequestSample(Instant timestamp, Scenario scenario, double orderMs, double downstreamMs,
                                String outcome, String traceId) {
        public boolean timedOut() { return "timeout".equals(outcome); }
    }
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message) {}
    public record Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                           int requestCount, int normalCount, int timeoutCount, long recordedRequestCount, double orderP95Ms,
                           double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms,
                           List<ErrorEntry> errors, boolean synthetic) {}

    private static final int MAX_SAMPLES = 10_000;
    private final Deque<RequestSample> samples = new ArrayDeque<>();
    private final MeterRegistry metrics;
    private final ErrorJournal errors;
    private final String serviceId;
    private final String downstreamService;
    private Scenario scenario = Scenario.NORMAL;
    private Instant droppedThrough;

    public ObservationStore(MeterRegistry metrics, ErrorJournal errors,
                            @Value("${sample.service-id:order-service}") String serviceId,
                            @Value("${sample.downstream-service:inventory-service}") String downstreamService) {
        this.metrics = metrics; this.errors = errors;
        if (!serviceId.matches("[a-z][a-z0-9-]{0,63}") || !downstreamService.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid sample service identity");
        this.serviceId = serviceId; this.downstreamService = downstreamService;
    }

    public String serviceId() { return serviceId; }
    public String downstreamService() { return downstreamService; }

    public synchronized Scenario scenario() { return scenario; }
    public synchronized void scenario(Scenario value) { scenario = value; }

    public synchronized void reset() {
        samples.clear();
        droppedThrough = null;
        scenario = Scenario.NORMAL;
    }

    public synchronized void record(RequestSample sample) {
        samples.addLast(sample);
        while (samples.size() > MAX_SAMPLES) {
            Instant dropped = samples.removeFirst().timestamp();
            if (droppedThrough == null || dropped.isAfter(droppedThrough)) droppedThrough = dropped;
        }
        metrics.counter("sample.order.requests", "outcome", sample.outcome()).increment();
        metrics.timer("sample.order.duration", "outcome", sample.outcome())
            .record(Duration.ofNanos((long) (sample.orderMs() * 1_000_000)));
        metrics.timer("sample.inventory.call.duration", "outcome", sample.outcome())
            .record(Duration.ofNanos((long) (sample.downstreamMs() * 1_000_000)));
    }

    public synchronized Snapshot snapshot(int windowMinutes, Instant end) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new IllegalArgumentException("windowMinutes must be 1..60");
        Instant start = end.minus(windowMinutes, ChronoUnit.MINUTES);
        if (droppedThrough != null && !droppedThrough.isBefore(start))
            throw new ResponseStatusException(UNPROCESSABLE_ENTITY, "Observation window is no longer fully retained; reduce the window");
        List<RequestSample> matching = samples.stream()
            .filter(sample -> !sample.timestamp().isBefore(start) && !sample.timestamp().isAfter(end)).toList();
        List<RequestSample> normals = matching.stream().filter(sample -> sample.scenario() == Scenario.NORMAL && "ok".equals(sample.outcome())).toList();
        List<RequestSample> failures = matching.stream().filter(RequestSample::timedOut).toList();
        long recorded = Math.round(metrics.find("sample.order.requests").counters().stream().mapToDouble(Counter::count).sum());
        return new Snapshot(serviceId, scenario, start, end, matching.size(), normals.size(), failures.size(), recorded,
            p95(matching.stream().map(RequestSample::orderMs).toList()),
            p95(matching.stream().map(RequestSample::downstreamMs).toList()),
            matching.isEmpty() ? 0 : (double) failures.size() / matching.size(),
            normals.isEmpty() ? null : p95(normals.stream().map(RequestSample::orderMs).toList()),
            errors.recent(start, end, 3), false);
    }

    private static double p95(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        double value = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        return Math.round(value * 10.0) / 10.0;
    }
}
