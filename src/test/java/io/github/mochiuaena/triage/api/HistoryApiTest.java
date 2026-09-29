package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:history-api;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}",
    "triage.settings.key-file=target/history-test-key"
})
class HistoryApiTest {
    @Autowired TestRestTemplate http;
    @Autowired RunRepository runs;
    private Run row(Status status) {
        Instant time = Instant.now();
        var run = new Run(UUID.randomUUID(), "fixture private question", "archived-service", 5, Scenario.OBSERVED, "DEMO", false,
            status, time, status.terminal() ? time.plusMillis(1) : null, 0, List.of(), List.of(), null, null);
        runs.insert(run); return run;
    }
    private ResponseEntity<String> delete(UUID id, UUID confirm, String origin, boolean marker) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (marker) headers.set("X-Triage-History", "1"); if (origin != null) headers.set("Origin", origin);
        return http.exchange("/api/history/" + id, HttpMethod.DELETE, new HttpEntity<>(Map.of("confirmId", confirm), headers), String.class);
    }
    @Test void onlyConfirmedSameOriginTerminalDeletionRemovesTheEntireRecord() {
        var run = row(Status.SUCCEEDED);
        assertThat(delete(run.id(), run.id(), null, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(run.id(), run.id(), "https://invalid.example", true).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(run.id(), UUID.randomUUID(), null, true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(runs.find(run.id())).isPresent();
        assertThat(delete(run.id(), run.id(), null, true).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity("/api/runs/" + run.id(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/runs/" + run.id() + "/events", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(run.id(), run.id(), null, true).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void queuedAndRunningRecordsRemainAfterDeletionAttempts() {
        for (var status : List.of(Status.QUEUED, Status.RUNNING)) {
            var run = row(status);
            assertThat(delete(run.id(), run.id(), null, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(runs.find(run.id())).isPresent();
        }
    }
    @Test void newHistoryFiltersAndOldRecentArrayBothRemainUsable() {
        var run = row(Status.FAILED);
        var page = http.getForObject("/api/history?service=archived-service&status=FAILED&pageSize=20", HistoryRepository.Page.class);
        assertThat(page.items()).extracting(HistoryRepository.Entry::id).contains(run.id());
        assertThat(http.getForObject("/api/runs?limit=50", RunSummary[].class)).isNotEmpty();
        for (String query : List.of("pageSize=51", "cursor=invalid-private-cursor", "mode=WRONG", "status=WRONG",
                "from=2026-09-30T00:00:00Z&until=2026-09-29T00:00:00Z")) {
            var invalid = http.getForEntity("/api/history?" + query, String.class);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(invalid.getBody()).doesNotContain("invalid-private-cursor", "fixture private question");
        }
    }
}
