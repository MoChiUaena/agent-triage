package io.github.mochiuaena.triage.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class HistoryRepositoryTest {
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RunRepository runs;
    private HistoryRepository history;
    private final Instant time = Instant.parse("2026-09-29T00:00:00.123456789Z");
    @BeforeEach void schema() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__execution_records.sql"),
            new ClassPathResource("db/migration/V3__history_projection.sql")).execute(source);
        jdbc = new JdbcTemplate(source); runs = new RunRepository(jdbc, json); history = new HistoryRepository(jdbc, json, runs);
    }
    static Run run(Instant time, String service, Status status, String question, ModelExecution model) {
        return new Run(UUID.randomUUID(), question, service, 5, Scenario.OBSERVED, model == null ? "DEMO" : "MODEL", false,
            status, time, status.terminal() ? time.plusMillis(500) : null, 3, List.of(), List.of(), null, null, model,
            new ServiceInfo(service, service, "stock-service", "库存"));
    }
    @Test void cursorHandlesDatabaseTimestampRoundingAndNewRowsWithoutDuplicatesOrGaps() {
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 73; i++) { var run = run(time, "past-service", Status.SUCCEEDED, "历史请求 " + i, null); expected.add(run.id()); runs.insert(run); }
        var filter = new HistoryFilter(null, null, null, null, null, null);
        var page = history.page(filter, 20, null);
        Set<UUID> seen = new HashSet<>();
        runs.insert(run(time.plusSeconds(1), "new-service", Status.SUCCEEDED, "新请求", null));
        while (true) {
            for (var entry : page.items()) assertThat(seen.add(entry.id())).isTrue();
            if (page.nextCursor() == null) break;
            page = history.page(filter, 20, page.nextCursor());
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
    }
    @Test void literalSearchStatusModeDatesAndRemovedServiceAreSupported() {
        var model = new ModelExecution("test", "test", 1, new TokenUsage(3, 2, 5));
        var wanted = run(time, "removed-service", Status.FAILED, "超时 10%_!", model);
        runs.insert(wanted); runs.insert(run(time.plusSeconds(2), "removed-service", Status.SUCCEEDED, "超时 10000", null));
        var filter = new HistoryFilter("%_!", "removed-service", Status.FAILED, "MODEL", time.minusSeconds(1), time.plusSeconds(1));
        var page = history.page(filter, 20, null);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(HistoryRepository.Entry::id).containsExactly(wanted.id());
        assertThat(history.services()).extracting(HistoryRepository.Service::id).contains("removed-service");
    }
    @Test void cursorCannotBeReusedWithDifferentFilters() {
        for (int i = 0; i < 2; i++) runs.insert(run(time, "order-service", Status.SUCCEEDED, "订单", null));
        var filter = new HistoryFilter(null, null, null, null, null, null);
        String cursor = history.page(filter, 1, null).nextCursor();
        assertThatThrownBy(() -> history.page(new HistoryFilter("变更", null, null, null, null, null), 1, cursor)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void oldJsonIsIndexedWithoutRewritingItsPayloadAndActiveRecordsCannotBeDeleted() throws Exception {
        var old = run(time, "order-service", Status.SUCCEEDED, "旧记录", null);
        String payload = json.writeValueAsString(old);
        jdbc.update("INSERT INTO triage_runs (id,created_at,status,payload) VALUES (?,?,?,?)", old.id().toString(),
            OffsetDateTime.ofInstant(time, ZoneOffset.UTC), old.status().name(), payload);
        history.indexLegacy();
        assertThat(jdbc.queryForObject("SELECT payload FROM triage_runs WHERE id=?", String.class, old.id().toString())).isEqualTo(payload);
        assertThat(history.page(new HistoryFilter(null, "order-service", null, null, null, null), 20, null).total()).isEqualTo(1);
        var active = run(time, "order-service", Status.RUNNING, "运行中", null); runs.insert(active);
        assertThat(history.deleteTerminal(active.id())).isFalse();
        assertThat(runs.find(active.id())).isPresent();
        assertThat(history.deleteTerminal(old.id())).isTrue();
        assertThat(runs.find(old.id())).isEmpty();
    }
}
