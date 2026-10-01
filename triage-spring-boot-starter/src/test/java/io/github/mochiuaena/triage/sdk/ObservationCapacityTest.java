package io.github.mochiuaena.triage.sdk;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ObservationCapacityTest {
    private static final Instant END = Instant.parse("2026-10-01T00:00:00Z");
    private static final MvcEndpoint FIRST = endpoint(1);
    private static final MvcEndpoint SECOND = endpoint(2);

    @Test void concurrentWritersAndReadersSeeConsistentCountsUntilCapacityIsExceeded() throws Exception {
        var properties = httpProperties(512);
        var recorder = new ObservationRecorder(properties, Clock.fixed(END, ZoneOffset.UTC));
        var start = new CountDownLatch(1);
        var remaining = new AtomicInteger(8);
        try (var workers = Executors.newFixedThreadPool(9)) {
            var reader = workers.submit(() -> {
                start.await();
                do {
                    var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(1, END, null);
                    assertThat(value.requestCount()).isEqualTo(value.recordedRequestCount());
                    assertThat(value.responseStatuses().successful() + value.responseStatuses().serverError()).isEqualTo(value.requestCount());
                    assertThat(value.endpoints().stream().mapToInt(ObservationRecorder.EndpointSummary::requestCount).sum()).isEqualTo(value.requestCount());
                    assertThat(value.endpoints().stream().mapToInt(ObservationRecorder.EndpointSummary::timeoutCount).sum()).isEqualTo(value.timeoutCount());
                    Thread.yield();
                } while (remaining.get() > 0);
                return null;
            });
            var writes = new java.util.ArrayList<Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 64;
                writes.add(workers.submit(() -> {
                    try {
                        start.await();
                        for (int i = offset; i < offset + 64; i++)
                            recorder.recordHttp(20, 10, i % 4 == 0, false, "fixture-" + i, i % 2 == 0 ? FIRST : SECOND,
                                null, i % 2 == 0 ? 200 : 503);
                    } finally { remaining.decrementAndGet(); }
                    return null;
                }));
            }
            start.countDown();
            for (var write : writes) write.get(10, TimeUnit.SECONDS);
            reader.get(10, TimeUnit.SECONDS);
        }
        var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(1, END, null);
        assertThat(value.requestCount()).isEqualTo(512);
        assertThat(value.timeoutCount()).isEqualTo(128);
        assertThat(value.responseStatuses().successful()).isEqualTo(256);
        assertThat(value.responseStatuses().serverError()).isEqualTo(256);
        var legacy = (ObservationRecorder.HttpWindow) recorder.snapshot(1, END);
        assertThat(legacy.requestCount()).isEqualTo(512);
        recorder.recordHttp(20, 10, false, false, "overflow", FIRST);
        lost(() -> recorder.snapshot(1, END));
        lost(() -> recorder.endpointSnapshot(1, END, null));
        lost(() -> recorder.endpointSnapshot(1, END, SECOND.id()));
    }

    @Test void droppingAnotherEndpointDoesNotMakeAFilteredWindowAppearComplete() {
        var recorder = new ObservationRecorder(httpProperties(10), Clock.fixed(END, ZoneOffset.UTC));
        recorder.recordHttp(20, 10, true, false, "dropped", FIRST);
        for (int i = 0; i < 10; i++) recorder.recordHttp(20, 10, false, false, "kept", SECOND);
        lost(() -> recorder.endpointSnapshot(1, END, SECOND.id()));
    }

    @Test void droppedInclusiveBoundaryIsRejectedAndAStrictlyNewerWindowRecovers() {
        var time = new AtomicReference<>(END.minusSeconds(60));
        var recorder = new ObservationRecorder(httpProperties(10), clock(time));
        recorder.recordHttp(20, 10, true, false, "dropped", FIRST);
        time.set(END);
        for (int i = 0; i < 10; i++) recorder.recordHttp(20, 10, false, false, "kept", SECOND);
        lost(() -> recorder.endpointSnapshot(1, END, SECOND.id()));
        var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(1, END.plusNanos(1), SECOND.id());
        assertThat(value.requestCount()).isEqualTo(10);
        assertThat(value.timeoutCount()).isZero();
        assertThat(value.recordedRequestCount()).isEqualTo(11);
    }

    @Test void databaseRequestOverflowRejectsTheWindow() {
        var properties = ObservationRecorderTest.properties();
        properties.setKind(TriageObservationProperties.Kind.DATABASE); properties.setCapacity(10);
        var recorder = new ObservationRecorder(properties, Clock.fixed(END, ZoneOffset.UTC));
        recorder.configurePool(2);
        for (int i = 0; i < 10; i++) recorder.recordDatabase(4, 1, 3, null, "fixture");
        assertThat(recorder.databaseSnapshot(1, END).databasePool().queryCount()).isEqualTo(10);
        recorder.recordDatabase(4, 1, 3, null, "overflow");
        lost(() -> recorder.databaseSnapshot(1, END));
    }

    @Test void expiredSamplesStayLostIfTheWallClockMovesBackIntoTheirWindow() {
        var properties = httpProperties(10); properties.setMaxWindowMinutes(1);
        var time = new AtomicReference<>(END.minusSeconds(60));
        var recorder = new ObservationRecorder(properties, clock(time));
        recorder.recordHttp(300, 300, true, true, "expired-timeout", FIRST);
        time.set(END.plusSeconds(121));
        recorder.recordHttp(20, 10, false, false, "later", FIRST);
        time.set(END);
        recorder.recordHttp(20, 10, false, false, "after-clock-adjustment", FIRST);
        lost(() -> recorder.snapshot(1, END));
        lost(() -> recorder.endpointSnapshot(1, END, FIRST.id()));
    }

    @Test void poolSampleOverflowRejectsAWindowWithoutDroppingAnyDatabaseRequest() {
        var properties = ObservationRecorderTest.properties();
        properties.setKind(TriageObservationProperties.Kind.DATABASE); properties.setMaxWindowMinutes(1);
        var recorder = new ObservationRecorder(properties, Clock.fixed(END, ZoneOffset.UTC));
        recorder.recordDatabase(4, 1, 3, null, "fixture");
        for (int i = 0; i < 3_620; i++) recorder.pool(2, 2, 1);
        assertThat(recorder.databaseSnapshot(1, END).databasePool().poolSamples()).isEqualTo(3_620);
        recorder.pool(2, 2, 1);
        lost(() -> recorder.databaseSnapshot(1, END));
    }

    private static TriageObservationProperties httpProperties(int capacity) {
        var properties = ObservationRecorderTest.properties(); properties.setCapacity(capacity);
        properties.setEndpointObservations(true); properties.setResponseStatusCounts(true);
        return properties;
    }
    private static MvcEndpoint endpoint(int id) {
        return new MvcEndpoint("EP-" + "%032x".formatted(id), "GET", "/api/items" + id,
            "example.Controller", "item" + id, List.of(), "MVC_SELECTED");
    }
    private static Clock clock(AtomicReference<Instant> time) {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time.get(), zone); }
            @Override public Instant instant() { return time.get(); }
        };
    }
    private static void lost(Runnable query) {
        assertThatThrownBy(query::run).isInstanceOfSatisfying(ResponseStatusException.class,
            failure -> assertThat(failure.getStatusCode().value()).isEqualTo(422));
    }
}
