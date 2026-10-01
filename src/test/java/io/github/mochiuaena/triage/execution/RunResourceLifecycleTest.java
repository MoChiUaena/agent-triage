package io.github.mochiuaena.triage.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.ToolContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RunResourceLifecycleTest {
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
        @Override public void close() { service.close(); pool.close(); }
    }
}
