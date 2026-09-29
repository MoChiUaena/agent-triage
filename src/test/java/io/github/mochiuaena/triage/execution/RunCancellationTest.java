package io.github.mochiuaena.triage.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RunCancellationTest {
    private RunRepository repository;
    private RunService service;
    @BeforeEach void database() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE triage_runs (id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE, status VARCHAR(32), payload TEXT)");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V3__history_projection.sql"),
            new org.springframework.core.io.ClassPathResource("db/migration/V6__endpoint_history.sql")).execute(jdbc.getDataSource());
        repository = new RunRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }
    @AfterEach void close() { if (service != null) service.close(); }
    private void configure(String model, Function<ExecutionSession, TriageEngine.Decision> action, List<ReadOnlyTool> tools) {
        var engine = new TriageEngine() {
            public String mode() { return model == null ? "DEMO" : "MODEL"; }
            public String modelName() { return model; }
            public Decision investigate(ExecutionSession session) { return action.apply(session); }
        };
        service = new RunService(repository, new ExecutionLimits(3, Duration.ofSeconds(5), Duration.ofSeconds(20)), engine, tools);
    }
    private Run submit() { return service.submit("订单请求为什么慢", new ToolContext("order-service", 5, Scenario.NORMAL, Instant.now())); }
    private Run read(Run run) { return repository.find(run.id()).orElseThrow(); }
    private TriageEngine.Decision insufficient() { return new TriageEngine.Decision(Status.INSUFFICIENT_EVIDENCE, new Diagnosis(List.of(), List.of(), List.of("补充证据。"), "证据不足。")); }
    private ReadOnlyTool tool(String name, java.util.function.Supplier<List<Evidence>> action) {
        return new ReadOnlyTool() { public String name() { return name; } public List<Evidence> execute(ToolContext context, String query) { return action.get(); } };
    }
    private void uninterruptible(CountDownLatch release, CountDownLatch interrupted) {
        while (release.getCount() != 0) try { release.await(); } catch (InterruptedException e) { interrupted.countDown(); }
    }

    @Test void cancelsRunningToolPreservesEvidenceAndRejectsLateResults() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var finished = new CountDownLatch(1);
        var early = new Evidence("early", "first", "已收集", "先前观测", Map.of());
        configure(null, session -> { session.callTool("first", ""); session.callTool("slow", ""); return insufficient(); }, List.of(
            tool("first", () -> List.of(early)), tool("slow", () -> { entered.countDown(); uninterruptible(release, interrupted); finished.countDown(); return List.of(new Evidence("late", "slow", "迟到", "迟到结果", Map.of())); })));
        Run run = submit(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            Run cancelled = service.cancel(run.id());
            assertThat(cancelled.status()).isEqualTo(Status.CANCELLED); assertThat(cancelled.failure()).isNull(); assertThat(cancelled.diagnosis()).isNull();
            assertThat(cancelled.evidence()).containsExactly(early); assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(run.id())).isEqualTo(cancelled);
            release.countDown(); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(read(run)).isEqualTo(cancelled);
            assertThat(cancelled.events()).extracting(Event::type).endsWith("RUN_CANCELLED").doesNotContain("RUN_COMPLETED");
        } finally { release.countDown(); }
    }

    @Test void queuedCancellationNeverStartsTheCoordinatorAndIsIdempotent() throws Exception {
        var entered = new CountDownLatch(4); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        configure(null, session -> { calls.incrementAndGet(); entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return insufficient(); }, List.of());
        var running = new ArrayList<Run>(); for (int i = 0; i < 4; i++) running.add(submit());
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        Run queued = submit();
        Run cancelled = service.cancel(queued.id());
        assertThat(cancelled.events()).extracting(Event::type).containsExactly("RUN_QUEUED", "RUN_CANCELLED");
        assertThat(cancelled.toolCalls()).isZero(); assertThat(service.cancel(queued.id())).isEqualTo(cancelled);
        release.countDown(); await().atMost(Duration.ofSeconds(3)).until(() -> running.stream().allMatch(r -> read(r).status().terminal()));
        assertThat(calls).hasValue(4); assertThat(read(queued).status()).isEqualTo(Status.CANCELLED);
    }

    @Test void cancelsModelWaitAndPreventsAnotherRoundOrLateCompletion() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var finished = new CountDownLatch(1); var calls = new AtomicInteger();
        configure("local-model", session -> {
            session.callModel(() -> { calls.incrementAndGet(); entered.countDown(); uninterruptible(release, interrupted); finished.countDown(); return "late"; }, Duration.ofSeconds(5), 4);
            session.callModel(() -> { calls.incrementAndGet(); return "next"; }, Duration.ofSeconds(5), 4);
            return insufficient();
        }, List.of());
        Run run = submit(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            Run cancelled = service.cancel(run.id()); assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelled.modelExecution().calls()).isEqualTo(1); assertThat(cancelled.modelExecution().usage()).isNull();
            release.countDown(); assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(1); assertThat(read(run)).isEqualTo(cancelled);
            assertThat(cancelled.events()).extracting(Event::type).doesNotContain("MODEL_COMPLETED", "RUN_FAILED", "RUN_COMPLETED");
        } finally { release.countDown(); }
    }

    @Test void completedRunsAndMissingIdsHaveStableSemantics() {
        configure(null, session -> insufficient(), List.of());
        Run run = submit(); await().atMost(Duration.ofSeconds(2)).until(() -> read(run).status().terminal());
        assertThat(service.cancel(run.id())).isEqualTo(read(run));
        assertThatThrownBy(() -> service.cancel(UUID.randomUUID())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void cancellationAndCompletionCannotBothPublishTerminalEvents() throws Exception {
        for (int i = 0; i < 12; i++) {
            if (service != null) service.close();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            configure(null, session -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return insufficient(); }, List.of());
            Run run = submit(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown(); service.cancel(run.id());
            await().atMost(Duration.ofSeconds(2)).until(() -> read(run).status().terminal());
            Run terminal = read(run);
            assertThat(terminal.status()).isIn(Status.CANCELLED, Status.INSUFFICIENT_EVIDENCE);
            assertThat(terminal.events().stream().filter(e -> Set.of("RUN_CANCELLED", "RUN_COMPLETED").contains(e.type())).count()).isEqualTo(1);
        }
    }

    @Test void cancellationKeepsKnownUsageFromPreviousReplies() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        configure("local-model", session -> {
            session.callModel(() -> "first", Duration.ofSeconds(5), 4, reply -> session.recordModelUsage("local-model", new TokenUsage(100, 20, 120)));
            session.callModel(() -> { entered.countDown(); uninterruptible(release, interrupted); return "late"; }, Duration.ofSeconds(5), 4,
                reply -> session.recordModelUsage("local-model", new TokenUsage(200, 30, 230)));
            return insufficient();
        }, List.of());
        Run run = submit(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            var model = service.cancel(run.id()).modelExecution();
            assertThat(model.calls()).isEqualTo(2); assertThat(model.completedCalls()).isEqualTo(1); assertThat(model.usageReportedCalls()).isEqualTo(1);
            assertThat(model.knownUsage()).isEqualTo(new TokenUsage(100, 20, 120)); assertThat(model.usage()).isNull();
        } finally { release.countDown(); }
    }

    @Test void missingUsageDoesNotEraseLaterKnownUsageOrClaimACompleteTotal() {
        configure("local-model", session -> {
            session.callModel(() -> "first", Duration.ofSeconds(2), 4, reply -> session.recordModelUsage("local-model", null));
            session.callModel(() -> "second", Duration.ofSeconds(2), 4, reply -> session.recordModelUsage("local-model", new TokenUsage(100, 20, 120)));
            return insufficient();
        }, List.of());
        Run run = submit(); await().atMost(Duration.ofSeconds(2)).until(() -> read(run).status().terminal());
        var model = read(run).modelExecution();
        assertThat(model.usage()).isNull(); assertThat(model.knownUsage()).isEqualTo(new TokenUsage(100, 20, 120));
        assertThat(model.completedCalls()).isEqualTo(2); assertThat(model.usageReportedCalls()).isEqualTo(1);
    }

    @Test void oldModelExecutionJsonRemainsReadableWithoutUsageCoverage() throws Exception {
        var model = new ObjectMapper().readValue("{\"configuredModel\":\"legacy\",\"responseModel\":null,\"calls\":1,\"usage\":null}", ModelExecution.class);
        assertThat(model.knownUsage()).isNull(); assertThat(model.completedCalls()).isNull(); assertThat(model.usageReportedCalls()).isNull();
    }
}
