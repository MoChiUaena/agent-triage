package io.github.mochiuaena.triage.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.ToolContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import io.github.mochiuaena.triage.tools.ReadOnlyTool;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RunResourceLifecycleTest {
    @Test void repeatedToolAndModelCancellationReturnsWorkersQueuesAndConnectionsToIdle() throws Exception {
        var current = new AtomicReference<Batch>();
        var engine = new TriageEngine() {
            public String mode() { return "MODEL"; }
            public String modelName() { return "local-resource-test"; }
            public Decision investigate(ExecutionSession session) {
                Batch batch = current.get();
                if (batch == null) return insufficient();
                if (batch.model) session.callModel(() -> { batch.block(); return "ignored"; }, Duration.ofSeconds(10), 4);
                else session.callTool("blocking", "");
                return insufficient();
            }
        };
        var tool = new ReadOnlyTool() {
            public String name() { return "blocking"; }
            public List<Evidence> execute(ToolContext context, String query) { current.get().block(); return List.of(); }
        };
        long seconds = Long.getLong("triage.resource.seconds", 0L);
        long resourceStarted = System.nanoTime();
        resourcePhase("running", resourceStarted);
        long end = resourceStarted + TimeUnit.SECONDS.toNanos(seconds);
        int cycles = 0;
        long baselineHeap = 0, peakHeap = 0;
        try (var fixture = new Fixture(engine, List.of(tool))) {
            do {
                var batch = new Batch(cycles % 2 == 0); current.set(batch);
                var running = new ArrayList<Run>();
                try {
                    for (int i = 0; i < 4; i++) running.add(fixture.submit());
                    assertThat(batch.entered.await(5, TimeUnit.SECONDS)).isTrue();
                    Run queued = fixture.submit();
                    var cancelled = fixture.service.cancel(queued.id());
                    assertThat(cancelled.events()).extracting(Event::type).containsExactly("RUN_QUEUED", "RUN_CANCELLED");
                    for (var run : running) assertThat(fixture.service.cancel(run.id()).status()).isEqualTo(Status.CANCELLED);
                    assertThat(batch.finished.await(5, TimeUnit.SECONDS)).isTrue();
                    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                        assertThat(active(fixture.service)).isEmpty();
                        for (String name : List.of("coordinators", "toolWorkers", "modelWorkers")) {
                            var worker = executor(fixture.service, name);
                            assertThat(worker.getActiveCount()).isZero(); assertThat(worker.getQueue()).isEmpty();
                            assertThat(worker.getPoolSize()).isLessThanOrEqualTo(4);
                        }
                    });
                    for (var run : running) {
                        var saved = fixture.repository.find(run.id()).orElseThrow();
                        assertThat(saved.status()).isEqualTo(Status.CANCELLED);
                        assertThat(saved.events()).extracting(Event::type).doesNotContain("RUN_COMPLETED", "MODEL_COMPLETED", "TOOL_COMPLETED");
                    }
                    current.set(null);
                    var fresh = fixture.submit();
                    await().atMost(Duration.ofSeconds(3)).until(() -> fixture.repository.find(fresh.id()).orElseThrow().status().terminal());
                    assertThat(fixture.repository.find(fresh.id()).orElseThrow().status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
                    await().atMost(Duration.ofSeconds(3)).until(() -> active(fixture.service).isEmpty());
                    fixture.jdbc.update("DELETE FROM triage_runs WHERE status IN ('CANCELLED', 'INSUFFICIENT_EVIDENCE')");
                    assertThat(fixture.pool.getHikariPoolMXBean().getActiveConnections()).isZero();
                } finally { batch.release.countDown(); }
                cycles++;
                if (cycles == 8 || cycles % 100 == 0) {
                    long heap = retainedHeap();
                    if (cycles == 8) baselineHeap = heap;
                    peakHeap = Math.max(peakHeap, heap);
                    assertThat(heap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
                }
            } while (cycles < 16 || System.nanoTime() < end);
            long finalHeap = retainedHeap();
            assertThat(finalHeap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
            System.out.printf("RESOURCE_RESULT agent seconds=%d cycles=%d cancelled=%d fresh=%d baselineHeap=%d peakHeap=%d finalHeap=%d registry=0 queues=0 connections=0%n",
                seconds, cycles, cycles * 5, cycles, baselineHeap, peakHeap, finalHeap);
        }
        resourcePhase("closed", resourceStarted);
    }

    @Test void shutdownCancelsDrainedCoordinatorsAndReleasesTheirRunRegistryEntries() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var engine = new TriageEngine() {
            public String mode() { return "DEMO"; }
            public Decision investigate(ExecutionSession session) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return insufficient();
            }
        };
        try (var fixture = new Fixture(engine, List.of())) {
            for (int i = 0; i < 4; i++) fixture.submit();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var queued = new ArrayList<Run>();
            for (int i = 0; i < 6; i++) queued.add(fixture.submit());
            assertThat(active(fixture.service)).hasSize(10);
            fixture.service.close();
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(active(fixture.service)).isEmpty());
            for (var run : queued) assertThat(fixture.repository.find(run.id()).orElseThrow().status()).isEqualTo(Status.QUEUED);
            for (String name : List.of("coordinators", "toolWorkers", "modelWorkers"))
                await().atMost(Duration.ofSeconds(3)).until(() -> executor(fixture.service, name).isTerminated());
        } finally { release.countDown(); }
    }

    private static TriageEngine.Decision insufficient() {
        return new TriageEngine.Decision(Status.INSUFFICIENT_EVIDENCE,
            new Diagnosis(List.of(), List.of(), List.of("补充证据。"), "证据不足。"));
    }
    private static Map<?, ?> active(RunService service) {
        return (Map<?, ?>) ReflectionTestUtils.getField(service, "active");
    }
    private static ThreadPoolExecutor executor(RunService service, String name) {
        return (ThreadPoolExecutor) ReflectionTestUtils.getField(service, name);
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
    private static final class Batch {
        final boolean model;
        final CountDownLatch entered = new CountDownLatch(4), finished = new CountDownLatch(4), release = new CountDownLatch(1);
        Batch(boolean model) { this.model = model; }
        void block() {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        }
    }
    private static final class Fixture implements AutoCloseable {
        final HikariDataSource pool = new HikariDataSource();
        final JdbcTemplate jdbc;
        final RunRepository repository;
        final RunService service;
        Fixture(TriageEngine engine, List<io.github.mochiuaena.triage.tools.ReadOnlyTool> tools) {
            pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID()); pool.setMaximumPoolSize(2);
            jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE TABLE triage_runs (id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE, status VARCHAR(32), payload TEXT)");
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V3__history_projection.sql"),
                new ClassPathResource("db/migration/V6__endpoint_history.sql")).execute(pool);
            repository = new RunRepository(jdbc, new ObjectMapper().findAndRegisterModules());
            service = new RunService(repository, new ExecutionLimits(3, Duration.ofSeconds(10), Duration.ofSeconds(20)), engine, tools);
        }
        Run submit() { return service.submit("订单请求为什么慢", new ToolContext("order-service", 5, Scenario.NORMAL, Instant.now())); }
        @Override public void close() {
            service.close(); pool.close();
            for (String name : List.of("coordinators", "toolWorkers", "modelWorkers"))
                await().atMost(Duration.ofSeconds(3)).until(() -> executor(service, name).isTerminated());
        }
    }
}
