package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ToolsTest {
    private final Instant end = Instant.parse("2026-09-27T00:00:00Z");
    private ToolContext context(Scenario scenario) { return new ToolContext("order-service", 1, scenario, end); }

    @Test void rejectsUnboundedWindowsAndUnknownServices() {
        assertThatThrownBy(() -> new ToolContext("billing", 15, Scenario.NORMAL, end)).isInstanceOf(IllegalArgumentException.class);
        for (int minutes : List.of(0, 61, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> new ToolContext("order-service", minutes, Scenario.NORMAL, end)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void metricsDistinguishNormalAndTimeoutWithFrozenWindow() {
        MetricsTool tool = new MetricsTool();
        assertThat(tool.execute(context(Scenario.NORMAL), "").getFirst().data()).containsEntry("orderP95Ms", 120);
        assertThat(tool.execute(context(Scenario.DOWNSTREAM_TIMEOUT), "").getFirst().data())
            .containsEntry("orderP95Ms", 2350).containsEntry("windowStart", "2026-09-26T23:59:00Z");
    }

    @Test void logsAreBoundedAndFallInsideEvenTheSmallestWindow() {
        ErrorLogsTool tool = new ErrorLogsTool();
        assertThat(tool.execute(context(Scenario.NORMAL), "").getFirst().data()).containsEntry("returnedCount", 0);
        var evidence = tool.execute(context(Scenario.DOWNSTREAM_TIMEOUT), "").getFirst();
        @SuppressWarnings("unchecked") var entries = (List<Map<String, Object>>) evidence.data().get("entries");
        assertThat(entries).hasSize(3).allSatisfy(entry ->
            assertThat(Instant.parse(entry.get("timestamp").toString())).isBetween(end.minusSeconds(60), end));
    }

    @Test void documentsHaveStableIdsAndUnrelatedQueriesReturnNothing() throws Exception {
        RunbookSearchTool search = new RunbookSearchTool();
        assertThat(search.execute(context(Scenario.NORMAL), "订单超时")).hasSize(2)
            .extracting(e -> e.id()).contains("DOC-DOWNSTREAM-TIMEOUT#v1");
        assertThat(search.execute(context(Scenario.NORMAL), "天气预报")).isEmpty();
        assertThatThrownBy(() -> search.execute(context(Scenario.NORMAL), "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void liveRunbooksUseTheirOwnVersionAndDoNotClaimSyntheticObservations() throws Exception {
        RunbookSearchTool search = new RunbookSearchTool(new ObservationSource("LIVE", "http://127.0.0.1:18082"));
        var evidence = search.execute(context(Scenario.DOWNSTREAM_TIMEOUT), "订单超时");
        assertThat(evidence).extracting(item -> item.id()).contains("DOC-DOWNSTREAM-TIMEOUT#v2");
        assertThat(evidence.getFirst().data()).containsEntry("synthetic", false);
        assertThat(evidence.getFirst().summary()).contains("300ms").doesNotContain("2000ms");
    }

    @Test void observationSourceCannotTargetRemoteOrNonHttpUrls() {
        assertThatThrownBy(() -> new ObservationSource("LIVE", "https://127.0.0.1:18082"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationSource("LIVE", "http://example.com:18082"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationSource("LIVE", "http://127.0.0.1:18082/path"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void liveSearchIncludesServiceRulesWithoutPretendingTheyMatchedTheQuery() throws Exception {
        var live = new RunbookSearchTool(new ObservationSource("LIVE", "http://127.0.0.1:18082"));
        var results = live.execute(context(Scenario.NORMAL), "验证原因");
        assertThat(results).extracting(item -> item.id()).containsExactly(
            "DOC-DOWNSTREAM-TIMEOUT#v2", "DOC-HEALTHY-BASELINE#v2", "DOC-EVIDENCE-LIMITS#v1");
        assertThat(results.get(1).data()).containsEntry("queryMatched", false).containsEntry("serviceReference", true)
            .containsEntry("retrieval", "keyword+service-reference");
        assertThat(results.get(2).data()).containsEntry("queryMatched", true).containsEntry("serviceReference", false);
        assertThat(new RunbookSearchTool().execute(context(Scenario.NORMAL), "验证原因"))
            .extracting(item -> item.id()).containsExactly("DOC-EVIDENCE-LIMITS#v1");
    }
}
