package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.WorkflowModels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowLifecycleTest {
    final UUID repository = UUID.randomUUID();
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    WorkflowStore store;
    WorkflowService service;
    @BeforeEach void init() throws Exception {
        jdbc.execute("CREATE TABLE github_repositories (id VARCHAR(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO github_repositories (id) VALUES (?)", repository.toString());
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V11__development_workflows.sql")));
        store = new WorkflowStore(jdbc, json);
    }
    Plan plan() {
        return new Plan(UUID.randomUUID(), repository, "acme/service", new PrModels.Context(7,"Change","https://github.com/acme/service/pull/7","open",false,"a".repeat(40),"b".repeat(40),0,0,0,List.of(),false),
            null,"NONE",null,null,Instant.now(),Instant.now().plusSeconds(300),List.of(new Spec("REPOSITORY","Repository",List.of(),List.of())),"Fixture");
    }
    WorkflowService service(Duration deadline, CountDownLatch gate) {
        WorkflowPlanner planner = mock(WorkflowPlanner.class); WorkflowTasks tasks = mock(WorkflowTasks.class);
        when(planner.require(eq(repository),any())).thenAnswer(call -> plan());
        // Only the slow upstream stage is doubled; persistence, deadlines, admission and cancellation are real.
        when(tasks.execute(anyString(),any(),any(),anyList())).thenAnswer(call -> {
            try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new Result("SUCCEEDED","late upstream result",Data.empty(),List.of());
        });
        service = new WorkflowService(planner,tasks,store,mock(GithubRepositoryStore.class),deadline); return service;
    }
    @Test void deadlinePersistsATerminalSnapshotAndReleasesAdmissionForNewWork() {
        var gate = new CountDownLatch(1); var engine = service(Duration.ofMillis(100),gate);
        var run = engine.create(repository,UUID.randomUUID());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(store.require(run.id()).state()).isEqualTo("TIMED_OUT"));
        gate.countDown();
        // A late result must not turn the expired workflow into a successful one.
        assertThat(store.require(run.id()).state()).isEqualTo("TIMED_OUT");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThatCode(() -> engine.create(repository,UUID.randomUUID())).doesNotThrowAnyException());
    }
    @Test void twoBusyWorkflowsRejectAThirdWithoutCreatingADatabaseRow() {
        var gate = new CountDownLatch(1); var engine = service(Duration.ofSeconds(30),gate);
        var first = engine.create(repository,UUID.randomUUID()); var second = engine.create(repository,UUID.randomUUID());
        assertThatThrownBy(() -> engine.create(repository,UUID.randomUUID())).isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM development_workflows",Integer.class)).isEqualTo(2);
        engine.cancel(first.id()); engine.cancel(second.id()); gate.countDown();
    }
    @Test void startupRecoveryKeepsEvidenceAndInterruptsUnfinishedWorkInsteadOfReplayingIt() {
        Plan plan = plan(); Instant time = Instant.now(); UUID id = UUID.randomUUID();
        var evidence = Data.empty().repository(new RepositoryContext("acme/service","main",time,1,2,0,List.of()));
        store.insert(new Snapshot(id,repository,"acme/service",plan,"RUNNING","working",time,time,
            List.of(new Task("REPOSITORY","Repository",List.of(),List.of(),"RUNNING","reading","",time,null,List.of())),evidence));
        assertThat(store.recover()).isEqualTo(1);
        var recovered = store.require(id); assertThat(recovered.state()).isEqualTo("INTERRUPTED");
        assertThat(recovered.data().repository().openIssueSample()).isEqualTo(1);
        assertThat(recovered.tasks().getFirst().state()).isEqualTo("FAILED");
        assertThat(store.recover()).isZero();
    }
    @AfterEach void close() { if (service != null) service.close(); }
}
