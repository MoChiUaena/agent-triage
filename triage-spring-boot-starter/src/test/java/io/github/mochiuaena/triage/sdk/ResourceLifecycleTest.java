package io.github.mochiuaena.triage.sdk;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ResourceLifecycleTest {
    @Test void repeatedAsyncCompletionAndCancellationLeaveWorkersAndJdbcConnectionsIdle() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setCapacity(512); properties.setMaxWindowMinutes(1);
        properties.setKind(TriageObservationProperties.Kind.HTTP_REQUESTS);
        properties.setDownstreamId(null); properties.setDownstreamBaseUrl(null);
        properties.setEndpointObservations(true); properties.setResponseStatusCounts(true); properties.setRequestFailureCounts(true);
        properties.setJpaObservations(true); properties.setJpaServiceId("resource-db-service"); properties.setJpaDatabaseId("resource-db");
        properties.validate();
        var http = new ObservationRecorder(properties);
        var filter = new TriageRequestFilter(http);
        long seconds = Long.getLong("triage.resource.seconds", 0L);
        long resourceStarted = System.nanoTime();
        resourcePhase("running", resourceStarted);
        long end = resourceStarted + TimeUnit.SECONDS.toNanos(seconds);
        int cycles = 0;
        long baselineHeap = 0, peakHeap = 0;
        try (var pool = new HikariDataSource(); var observer = new TriageJpaObserver(properties);
             var workers = Executors.newFixedThreadPool(4)) {
            pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID()); pool.setMaximumPoolSize(2);
            DataSource source = observer.wrap(pool);
            var database = (ObservationRecorder) ReflectionTestUtils.getField(observer, "recorder");
            do {
                var request = new MockHttpServletRequest("GET", "/api/resource"); request.setAsyncSupported(true);
                var snapshot = new AtomicReference<TriageObservationContext.Snapshot>();
                var response = new MockHttpServletResponse(); response.setStatus(cycles % 3 == 0 ? 503 : 204);
                filter.doFilter(request, response, (req, res) -> {
                    req.startAsync(req, res); snapshot.set(TriageObservationContext.capture());
                });
                var first = workers.submit(snapshot.get().wrap((Callable<Integer>) () -> query(source)));
                var failed = workers.submit(snapshot.get().wrap((Callable<Void>) () -> {
                    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                        assertThatThrownBy(() -> statement.execute("SELECT * FROM resource_absent_table")).isInstanceOf(java.sql.SQLException.class);
                    }
                    return null;
                }));
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(42); failed.get(5, TimeUnit.SECONDS);
                var entered = new CountDownLatch(1); var finished = new CountDownLatch(1);
                var cancelled = workers.submit(snapshot.get().wrap((Runnable) () -> {
                    entered.countDown();
                    try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    finally { finished.countDown(); }
                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                cancelled.cancel(true); assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                request.getAsyncContext().complete();
                assertThat(TriageRequestFilter.CURRENT.get()).isNull();
                assertThat(workers.submit(snapshot.get().wrap((Callable<Integer>) () -> query(source))).get(5, TimeUnit.SECONDS)).isEqualTo(42);
                // Occupy all four workers together so every worker's ThreadLocal is checked.
                var ready = new CountDownLatch(4); var release = new CountDownLatch(1);
                var probes = new ArrayList<Future<Boolean>>();
                try {
                    for (int i = 0; i < 4; i++) probes.add(workers.submit(() -> {
                        boolean empty = TriageRequestFilter.CURRENT.get() == null;
                        ready.countDown(); release.await(); return empty;
                    }));
                    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
                    for (var probe : probes) assertThat(probe.get(5, TimeUnit.SECONDS)).isTrue();
                } finally { release.countDown(); }
                cycles++;
                synchronized (http) {
                    assertThat(ReflectionTestUtils.getField(http, "recorded")).isEqualTo((long) cycles);
                    assertThat(((Deque<?>) ReflectionTestUtils.getField(http, "http")).size()).isLessThanOrEqualTo(512);
                }
                synchronized (database) {
                    assertThat(ReflectionTestUtils.getField(database, "recorded")).isEqualTo(cycles * 2L);
                    assertThat(((Deque<?>) ReflectionTestUtils.getField(database, "database")).size()).isLessThanOrEqualTo(512);
                    assertThat(((Deque<?>) ReflectionTestUtils.getField(database, "pools")).size()).isLessThanOrEqualTo(3_620);
                }
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                if (cycles <= 512) {
                    var window = http.requestSnapshot(1, java.time.Instant.now(), null);
                    assertThat(window.requestCount()).isEqualTo(cycles);
                    assertThat(window.responseStatuses().serverError()).isEqualTo((cycles + 2) / 3);
                    assertThat(window.requestFailures().serverErrorResponses()).isEqualTo((cycles + 2) / 3);
                    assertThat(window.requestFailures().executionFailures()).isZero();
                }
                if (cycles % 200 == 0 && cycles > 512) {
                    assertThatThrownBy(() -> http.requestSnapshot(1, java.time.Instant.now(), null))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
                    assertThatThrownBy(() -> database.databaseSnapshot(1, java.time.Instant.now()))
                        .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
                }
                if (cycles == 16 || cycles % 200 == 0) {
                    long heap = retainedHeap();
                    if (cycles == 16) baselineHeap = heap;
                    peakHeap = Math.max(peakHeap, heap);
                    assertThat(heap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
                }
                Thread.sleep(10);
            } while (cycles < 40 || System.nanoTime() < end);
            long finalHeap = retainedHeap();
            assertThat(finalHeap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
            observer.close();
            var sampler = (TriageJdbcObserver) ReflectionTestUtils.getField(observer, "sampler");
            await().atMost(Duration.ofSeconds(3)).until(() -> scheduler(sampler).isTerminated());
            assertThat(pool.isClosed()).isFalse();
            System.out.printf("RESOURCE_RESULT starter seconds=%d cycles=%d requests=%d jdbc=%d cancelled=%d baselineHeap=%d peakHeap=%d finalHeap=%d workerContexts=0 connections=0 samplerStopped=true%n",
                seconds, cycles, cycles, cycles * 2, cycles, baselineHeap, peakHeap, finalHeap);
        }
        resourcePhase("closed", resourceStarted);
    }

    @Test void repeatedJdbcObserverCloseStopsItsSamplerAndPreservesTheApplicationsPool() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setKind(TriageObservationProperties.Kind.DATABASE);
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID()); pool.setMaximumPoolSize(1);
            for (int cycle = 0; cycle < 20; cycle++) {
                var recorder = new ObservationRecorder(properties);
                try (var observer = new TriageJdbcObserver(pool, recorder)) {
                    assertThat(observer.query(ResourceLifecycleTest::queryConnection)).isEqualTo(42);
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                    observer.close();
                    await().atMost(Duration.ofSeconds(3)).until(() -> scheduler(observer).isTerminated());
                    assertThat(pool.isClosed()).isFalse();
                }
            }
        }
    }

    @Test void retainedCompletedSnapshotDoesNotPinTheBusinessClassLoader() {
        var retained = completedSnapshot();
        try {
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                System.gc();
                assertThat(retained.loader().get()).isNull();
            });
            retained.snapshot().wrap((Runnable) () -> assertThat(TriageRequestFilter.CURRENT.get()).isNull()).run();
        } finally { Reference.reachabilityFence(retained.snapshot()); }
    }

    private record Retained(TriageObservationContext.Snapshot snapshot, WeakReference<ClassLoader> loader) {}
    private static Retained completedSnapshot() {
        var context = new TriageRequestFilter.Context();
        var loader = new ClassLoader(null) {};
        context.handlerClass = Proxy.newProxyInstance(loader, new Class<?>[]{Runnable.class}, (proxy, method, args) -> null).getClass();
        TriageRequestFilter.CURRENT.set(context);
        try {
            var snapshot = TriageObservationContext.capture();
            context.finish();
            return new Retained(snapshot, new WeakReference<>(loader));
        } finally { TriageRequestFilter.CURRENT.remove(); }
    }
    private static int query(DataSource source) throws java.sql.SQLException {
        try (var connection = source.getConnection()) { return queryConnection(connection); }
    }
    private static int queryConnection(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 42")) {
            result.next(); return result.getInt(1);
        }
    }
    private static ScheduledExecutorService scheduler(TriageJdbcObserver observer) {
        return (ScheduledExecutorService) ReflectionTestUtils.getField(observer, "sampler");
    }
    private static void resourcePhase(String phase, long started) throws Exception {
        String marker = System.getProperty("triage.resource.marker");
        if (marker == null) return;
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        java.nio.file.Files.writeString(java.nio.file.Path.of(marker),
            phase + "," + ProcessHandle.current().pid() + "," + elapsed);
        if (phase.equals("closed")) Thread.sleep(15_000); // Keep the owned test JVM available for one closing diagnostic.
    }
    private static long retainedHeap() {
        System.gc();
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
