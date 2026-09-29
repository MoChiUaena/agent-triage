package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:stats-api;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.settings.key-file=target/stats-test-key"
})
class StatisticsApiTest {
    @Autowired TestRestTemplate http;
    @Autowired RunRepository runs;
    private Run row(String service, Status status, Long duration, ModelExecution model, Instant created) {
        var run = new Run(UUID.randomUUID(), "statistical fixture", service, 5, Scenario.OBSERVED,
            model == null ? "DEMO" : "MODEL", false, status, created, duration == null ? null : created.plusMillis(duration),
            3, List.of(), List.of(), null, null, model);
        runs.insert(run); return run;
    }
    private HistoryRepository.Statistics statistics(String service) { return http.getForObject("/api/statistics?days=7&service=" + service, HistoryRepository.Statistics.class); }
    @Test void usageCoverageIsExplicitAndMissingTokensNeverBecomeReportedZeros() {
        String service = "stats-" + UUID.randomUUID(); Instant time = Instant.now().minusSeconds(10);
        var full = new ModelExecution("fixture", "fixture", 2, new TokenUsage(10, 5, 15));
        var partial = new ModelExecution("fixture", "fixture", 2, null, null, null, null, null, new TokenUsage(3, 2, 5), 1, 1);
        var missing = new ModelExecution("fixture", null, 1, null);
        row(service, Status.SUCCEEDED, 100L, full, time);
        Run cancelled = row(service, Status.CANCELLED, 300L, partial, time);
        row(service, Status.FAILED, 200L, missing, time);
        row(service, Status.RUNNING, null, null, time);
        row(service, Status.SUCCEEDED, 500L, full, time.minusSeconds(8 * 86400L));
        var result = statistics(service);
        assertThat(result.total()).isEqualTo(4);
        assertThat(result.modelCalls()).isEqualTo(5);
        assertThat(result.toolCalls()).isEqualTo(12);
        assertThat(result.usage().calledRuns()).isEqualTo(3);
        assertThat(result.usage().completeRuns()).isEqualTo(1);
        assertThat(result.usage().partialRuns()).isEqualTo(1);
        assertThat(result.usage().missingRuns()).isEqualTo(1);
        assertThat(result.usage().knownUsage()).isEqualTo(new TokenUsage(13, 7, 20));
        assertThat(result.durations().samples()).isEqualTo(3);
        assertThat(result.durations().averageMillis()).isEqualTo(200);
        assertThat(result.durations().p95Millis()).isEqualTo(300);
        assertThat(result.statuses().get(Status.RUNNING)).isEqualTo(1);
        var headers = new HttpHeaders(); headers.set("X-Triage-History", "1"); headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange("/api/history/" + cancelled.id(), HttpMethod.DELETE,
            new HttpEntity<>(Map.of("confirmId", cancelled.id()), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(statistics(service).usage().knownUsage()).isEqualTo(new TokenUsage(10, 5, 15));
    }
    @Test void emptyAndEntirelyMissingUsageRemainUnknownAndRangesAreBounded() {
        String service = "stats-" + UUID.randomUUID();
        var empty = statistics(service);
        assertThat(empty.total()).isZero(); assertThat(empty.usage().knownUsage()).isNull();
        assertThat(empty.durations().averageMillis()).isNull(); assertThat(empty.durations().p95Millis()).isNull();
        row(service, Status.FAILED, 200L, new ModelExecution("fixture", null, 1, null), Instant.now().minusSeconds(2));
        assertThat(statistics(service).usage().knownUsage()).isNull();
        assertThat(statistics(service).usage().missingRuns()).isEqualTo(1);
        for (int days : List.of(0, 91)) assertThat(http.getForEntity("/api/statistics?days=" + days, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
