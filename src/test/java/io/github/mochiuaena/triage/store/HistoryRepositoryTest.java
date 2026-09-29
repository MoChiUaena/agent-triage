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
            new ClassPathResource("db/migration/V3__history_projection.sql"),
            new ClassPathResource("db/migration/V6__endpoint_history.sql")).execute(source);
        jdbc = new JdbcTemplate(source); runs = new RunRepository(jdbc, json); history = new HistoryRepository(jdbc, json, runs);
    }
    static Run run(Instant time, String service, Status status, String question, ModelExecution model) {
        return new Run(UUID.randomUUID(), question, service, 5, Scenario.OBSERVED, model == null ? "DEMO" : "MODEL", false,
            status, time, status.terminal() ? time.plusMillis(500) : null, 3, List.of(), List.of(), null, null, model,
            new ServiceInfo(service, service, "stock-service", "库存"));
    }
    private Run endpointRun(String service, RequestEndpoint endpoint, Instant created) {
        var base = run(created, service, Status.SUCCEEDED, "同一个问题", null);
        return new Run(base.id(), base.question(), service, 5, base.scenario(), base.mode(), false, base.status(), created,
            base.finishedAt(), base.toolCalls(), base.events(), base.evidence(), null, null, null, base.serviceInfo(), null, endpoint);
    }
    private RequestEndpoint endpoint(char suffix, String route, String method) {
        return new RequestEndpoint("EP-" + String.valueOf(suffix).repeat(32), "GET", route, "example.ArchivedController", method,
            List.of("java.lang.String"), "MVC_HANDLER_SELECTED");
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
    @Test void endpointScopesSeparateServicesAndRetainRemovedOrChangedHandlers() {
        var oldEndpoint = endpoint('a', "/api/tickets/{id}", "ticket");
        var changedEndpoint = endpoint('b', "/api/tickets/{id}", "replacement");
        Run old = endpointRun("removed-service", oldEndpoint, time);
        Run newer = endpointRun("removed-service", changedEndpoint, time.plusSeconds(1));
        runs.insert(old); runs.insert(newer);
        runs.insert(endpointRun("removed-service", oldEndpoint, time.plusSeconds(2)));
        runs.insert(endpointRun("other-service", oldEndpoint, time));
        Run whole = run(time, "removed-service", Status.SUCCEEDED, "同一个问题", null); runs.insert(whole);
        var filter = new HistoryFilter(null, "removed-service", null, null, null, null, oldEndpoint.id());
        assertThat(history.page(filter, 20, null).items()).allSatisfy(entry -> {
            assertThat(entry.service()).isEqualTo("removed-service"); assertThat(entry.endpoint()).isEqualTo(oldEndpoint);
        }).hasSize(2);
        assertThat(history.endpoints("removed-service")).containsExactlyInAnyOrder(oldEndpoint, changedEndpoint);
        assertThat(history.endpoints("other-service")).containsExactly(oldEndpoint);
        assertThat(history.endpoints("missing-service")).isEmpty();
        assertThat(history.page(new HistoryFilter(null, "removed-service", null, null, null, null, "SERVICE"), 20, null).items())
            .extracting(HistoryRepository.Entry::id).containsExactly(whole.id());
        assertThat(history.statistics(filter).total()).isEqualTo(2);
        assertThat(history.statistics(filter).endpointId()).isEqualTo(oldEndpoint.id());
        assertThat(history.deleteTerminal(newer.id())).isTrue();
        assertThat(history.endpoints("removed-service")).containsExactly(oldEndpoint);
        assertThat(runs.find(old.id()).orElseThrow().endpoint()).isEqualTo(oldEndpoint);
        assertThatThrownBy(() -> new HistoryFilter(null, null, null, null, null, null, oldEndpoint.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.endpoints(" ")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void endpointSearchUsesLiteralRouteAndHandlerAndCursorRemainsBoundToScope() {
        var endpoint = endpoint('a', "/api/10%_!", "findTicket");
        Run first = endpointRun("archived-service", endpoint, time); runs.insert(first);
        runs.insert(endpointRun("archived-service", endpoint, time.plusSeconds(1)));
        runs.insert(endpointRun("archived-service", endpoint('b', "/api/10000", "other"), time));
        for (String keyword : List.of("%_!", "findTicket", "example.ArchivedController", "GET")) {
            var page = history.page(new HistoryFilter(keyword, "archived-service", null, null, null, null), 20, null);
            assertThat(page.total()).isEqualTo(List.of("GET", "example.ArchivedController").contains(keyword) ? 3 : 2);
        }
        var selected = new HistoryFilter(null, "archived-service", null, null, null, null, endpoint.id());
        String cursor = history.page(selected, 1, null).nextCursor();
        assertThat(history.page(selected, 1, cursor).items()).extracting(HistoryRepository.Entry::id).containsExactly(first.id());
        assertThatThrownBy(() -> history.page(new HistoryFilter(null, "archived-service", null, null, null, null, "SERVICE"), 1, cursor))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void versionedBackfillIndexesExistingServiceRowsAndNullScopesOnlyOnceWithoutChangingJson() throws Exception {
        Map<UUID, String> payloads = new HashMap<>();
        for (int i = 0; i < 205; i++) {
            Run run = endpointRun("legacy-service", i % 2 == 0 ? endpoint('a', "/api/tickets/{id}", "ticket") : null, time.plusSeconds(i));
            var tree = json.valueToTree(run); if (i % 2 == 1) ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("endpoint");
            String payload = json.writeValueAsString(tree); payloads.put(run.id(), payload);
            jdbc.update("INSERT INTO triage_runs (id,created_at,status,payload,service_id) VALUES (?,?,?,?,?)", run.id().toString(),
                OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC), run.status().name(), payload, "legacy-service");
        }
        history.indexLegacy(); history.indexLegacy();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM triage_runs WHERE history_version < 1", Long.class)).isZero();
        assertThat(history.statistics(new HistoryFilter(null, "legacy-service", null, null, null, null, "SERVICE")).total()).isEqualTo(102);
        assertThat(history.statistics(new HistoryFilter(null, "legacy-service", null, null, null, null, "EP-" + "a".repeat(32))).total()).isEqualTo(103);
        jdbc.query("SELECT id,payload FROM triage_runs", row -> { assertThat(row.getString(2)).isEqualTo(payloads.get(UUID.fromString(row.getString(1)))); });
        Run replacement = endpointRun("legacy-service", endpoint('b', "/api/summary", "summary"), time);
        runs.save(new Run(payloads.keySet().iterator().next(), replacement.question(), replacement.service(), 5, replacement.scenario(), replacement.mode(), false,
            replacement.status(), replacement.createdAt(), replacement.finishedAt(), replacement.toolCalls(), replacement.events(), replacement.evidence(), null, null, null,
            replacement.serviceInfo(), null, replacement.endpoint()));
        assertThat(history.page(new HistoryFilter(null, "legacy-service", null, null, null, null, replacement.endpoint().id()), 20, null).total()).isEqualTo(1);
    }
}
