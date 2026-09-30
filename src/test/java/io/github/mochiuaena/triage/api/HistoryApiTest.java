package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private Run row(Status status) { return row(status, Instant.now()); }
    private Run row(Status status, Instant time) {
        var run = new Run(UUID.randomUUID(), "fixture private question", "archived-service", 5, Scenario.OBSERVED, "DEMO", false,
            status, time, status.terminal() ? time.plusMillis(1) : null, 0, List.of(), List.of(), null, null);
        runs.insert(run); return run;
    }
    private Run endpointRow(String service, RequestEndpoint endpoint) {
        Instant time = Instant.now();
        var run = new Run(UUID.randomUUID(), "endpoint private fixture", service, 5, Scenario.OBSERVED, "DEMO", false,
            Status.SUCCEEDED, time, time.plusMillis(1), 0, List.of(), List.of(), null, null, null, null, null, endpoint);
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
    @Test void retentionRequiresPreviewAndConfirmationAndNeverRemovesActiveOrRecentRuns() {
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        Run completed = row(Status.SUCCEEDED, old), failed = row(Status.FAILED, old.plusSeconds(1));
        Run running = row(Status.RUNNING, old), recent = row(Status.SUCCEEDED);
        var preview = http.getForObject("/api/history/retention?days=90", HistoryRepository.RetentionPreview.class);
        assertThat(preview.eligibleCount()).isEqualTo(2);
        assertThat(http.getForEntity("/api/history/retention?days=1", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-History", "1");
        var confirmation = Map.of("cutoff", preview.cutoff().toString(), "expectedCount", 2, "confirmation", "删除旧记录");
        assertThat(http.postForEntity("/api/history/retention", confirmation, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/api/history/retention", HttpMethod.POST,
            new HttpEntity<>(Map.of("cutoff", preview.cutoff().toString(), "expectedCount", 3, "confirmation", "删除旧记录"), headers), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.exchange("/api/history/retention", HttpMethod.POST,
            new HttpEntity<>(Map.of("cutoff", Instant.now().toString(), "expectedCount", 2, "confirmation", "删除旧记录"), headers), String.class)
            .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var result = http.exchange("/api/history/retention", HttpMethod.POST, new HttpEntity<>(confirmation, headers),
            HistoryController.RetentionResult.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().deletedCount()).isEqualTo(2);
        assertThat(runs.find(completed.id())).isEmpty(); assertThat(runs.find(failed.id())).isEmpty();
        assertThat(runs.find(running.id())).isPresent(); assertThat(runs.find(recent.id())).isPresent();
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
    @Test void savedEndpointsRemainAvailableInHistoryAndRecentSummariesWithServiceScopedFilters() {
        String service = "history-" + UUID.randomUUID();
        var endpoint = new RequestEndpoint("EP-" + "a".repeat(32), "GET", "/api/tickets/{id}", "example.TicketController", "ticket",
            List.of("java.lang.String"), "MVC_HANDLER_SELECTED");
        Run selected = endpointRow(service, endpoint); endpointRow("other-" + UUID.randomUUID(), endpoint);
        Run whole = endpointRow(service, null);
        assertThat(http.getForObject("/api/history/endpoints?service=" + service, RequestEndpoint[].class)).containsExactly(endpoint);
        var page = http.getForObject("/api/history?service=" + service + "&endpointId=" + endpoint.id(), HistoryRepository.Page.class);
        assertThat(page.items()).extracting(HistoryRepository.Entry::id).containsExactly(selected.id());
        assertThat(page.items().getFirst().endpoint()).isEqualTo(endpoint);
        var allService = http.getForObject("/api/history?service=" + service + "&endpointId=SERVICE", HistoryRepository.Page.class);
        assertThat(allService.items()).extracting(HistoryRepository.Entry::id).containsExactly(whole.id());
        assertThat(allService.items().getFirst().endpoint()).isNull();
        assertThat(http.getForObject("/api/runs?limit=50", RunSummary[].class)).anySatisfy(summary -> {
            assertThat(summary.id()).isEqualTo(selected.id()); assertThat(summary.endpoint()).isEqualTo(endpoint);
        });
        assertThat(http.getForObject("/api/history?service=" + service + "&q=TicketController", HistoryRepository.Page.class).total()).isEqualTo(1);
    }
    @Test void endpointQueriesRejectMissingServiceInvalidIdsAndForeignOriginsWithoutReturningHandlerDetails() {
        for (String query : List.of("/api/history?endpointId=EP-" + "a".repeat(32), "/api/history?service=archived-service&endpointId=wrong",
                "/api/history/endpoints", "/api/history/endpoints?service=invalid_service", "/api/history/endpoints?service=")) {
            var invalid = http.getForEntity(query, String.class);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(invalid.getBody()).doesNotContain("private fixture", "TicketController", "invalid_service");
        }
        var headers = new HttpHeaders(); headers.setOrigin("https://invalid.example");
        assertThat(http.exchange("/api/history/endpoints?service=archived-service", HttpMethod.GET,
            new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
