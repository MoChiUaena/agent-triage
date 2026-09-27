package io.github.mochiuaena.triage.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class RunServiceTest {
    private RunRepository repository;
    private RunService service;
    private final DemoReasoner reasoner = new DemoReasoner();

    @BeforeEach void database() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE triage_runs (id VARCHAR(36) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE, status VARCHAR(32), payload TEXT)");
        ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
        repository = new RunRepository(jdbc, json);
    }

    @AfterEach void cleanup() { if (service != null) service.close(); }

    private void configure(int maxCalls, long toolMs, long runMs, List<ReadOnlyTool> tools) {
        service = new RunService(repository, new ExecutionLimits(maxCalls, Duration.ofMillis(toolMs), Duration.ofMillis(runMs)), reasoner, tools);
    }

    private Run execute(String question, Scenario scenario) {
        var queued = service.submit(question, new ToolContext("order-service", 15, scenario, Instant.now()));
        await().atMost(Duration.ofSeconds(4)).until(() -> repository.find(queued.id()).orElseThrow().status().terminal());
        return repository.find(queued.id()).orElseThrow();
    }

    private List<ReadOnlyTool> realTools() throws Exception { return List.of(new RunbookSearchTool(), new MetricsTool(), new ErrorLogsTool()); }

    @Test void timeoutScenarioHasOnlyCitationsCollectedByThisRunAndOrderedEvents() throws Exception {
        configure(3, 2000, 10000, realTools());
        Run run = execute("订单查询为什么变慢？", Scenario.DOWNSTREAM_TIMEOUT);
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(run.evidence()).hasSize(4);
        assertThat(run.events()).extracting(Event::sequence).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
        assertThatCode(() -> EvidenceValidator.validate(run.diagnosis(), run.evidence())).doesNotThrowAnyException();
        assertThat(run.diagnosis().possibleCauses().getFirst().text()).contains("可能");
        assertThat(repository.recent(1)).extracting(Run::id).containsExactly(run.id());
    }

    @Test void normalScenarioDoesNotClaimAProvenTimeout() throws Exception {
        configure(3, 2000, 10000, realTools());
        Run run = execute("订单查询为什么变慢？", Scenario.NORMAL);
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.diagnosis().possibleCauses().getFirst().text()).contains("未发现");
    }

    @Test void unrelatedQuestionIsInsufficientRatherThanFailed() throws Exception {
        configure(3, 2000, 10000, realTools());
        Run run = execute("今天的天气如何？", Scenario.NORMAL);
        assertThat(run.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(run.toolCalls()).isZero();
        assertThat(run.failure()).isNull();
    }

    @Test void missingRunbookRetainsObservationsButDoesNotInventCause() throws Exception {
        configure(3, 2000, 10000, realTools());
        Run run = execute("查看健康状态", Scenario.DOWNSTREAM_TIMEOUT);
        assertThat(run.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(run.diagnosis().observations()).hasSize(2);
        assertThat(run.diagnosis().possibleCauses()).isEmpty();
    }

    @Test void toolCallLimitStopsBeforeFourthOrUnbudgetedCall() throws Exception {
        configure(2, 2000, 10000, realTools());
        Run run = execute("订单查询", Scenario.NORMAL);
        assertThat(run.failure().code()).isEqualTo("TOOL_CALL_LIMIT");
        assertThat(run.toolCalls()).isEqualTo(2);
        assertThat(run.diagnosis()).isNull();
    }

    @Test void toolFailureIsPersistedWithoutLeakingExceptionDetails() {
        configure(3, 2000, 10000, List.of(tool(() -> { throw new IllegalStateException("secret-test-value"); })));
        Run run = execute("订单查询", Scenario.NORMAL);
        assertThat(run.status()).isEqualTo(Status.FAILED);
        assertThat(run.failure().code()).isEqualTo("TOOL_ERROR");
        assertThat(run.toString()).doesNotContain("secret-test-value");
        assertThat(run.events()).extracting(Event::type).contains("TOOL_FAILED", "RUN_FAILED");
    }

    @Test void timedOutToolIsInterruptedAndCannotPublishLateSuccess() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        configure(3, 80, 5000, List.of(tool(() -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return List.of();
        })));
        Run run = execute("订单查询", Scenario.NORMAL);
        assertThat(run.failure().code()).isEqualTo("TOOL_TIMEOUT");
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(run.events()).extracting(Event::type).doesNotContain("TOOL_COMPLETED", "RUN_COMPLETED");
    }

    @Test void overallBudgetCanExpireBeforePerToolTimeout() {
        configure(3, 1000, 80, List.of(tool(() -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return List.of();
        })));
        assertThat(execute("订单查询", Scenario.NORMAL).failure().code()).isEqualTo("RUN_TIMEOUT");
    }

    @Test void outputCountIsBounded() {
        var item = new Evidence("one", "test", "title", "summary", java.util.Map.of());
        configure(3, 2000, 10000, List.of(tool(() -> java.util.Collections.nCopies(6, item))));
        assertThat(execute("订单查询", Scenario.NORMAL).failure().code()).isEqualTo("TOOL_OUTPUT_LIMIT");
    }

    @Test void restartMarksUnfinishedRecordsFailedAndPreservesEvidence() throws Exception {
        configure(3, 2000, 10000, realTools());
        var id = UUID.randomUUID();
        repository.insert(new Run(id, "订单查询", "order-service", 15, Scenario.NORMAL, "DEMO", true,
            Status.RUNNING, Instant.now(), null, 0, List.of(), List.of(), null, null));
        service.recoverInterruptedRuns();
        assertThat(repository.find(id).orElseThrow().failure().code()).isEqualTo("SERVER_RESTARTED");
    }

    @Test void fabricatedAndDuplicateEvidenceReferencesAreRejected() {
        var diagnosis = new Diagnosis(List.of(new Finding("invented", List.of("missing"))), List.of(), List.of(), "");
        assertThatThrownBy(() -> EvidenceValidator.validate(diagnosis, List.of())).isInstanceOf(IllegalArgumentException.class);
        var evidence = new Evidence("same", "test", "t", "s", java.util.Map.of());
        assertThatThrownBy(() -> EvidenceValidator.validate(diagnosis, List.of(evidence, evidence))).isInstanceOf(IllegalArgumentException.class);
    }

    private ReadOnlyTool tool(java.util.function.Supplier<List<Evidence>> action) {
        return new ReadOnlyTool() {
            public String name() { return "test_tool"; }
            public List<Evidence> execute(ToolContext context, String query) { return action.get(); }
        };
    }
}
