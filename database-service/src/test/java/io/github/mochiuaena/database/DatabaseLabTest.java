package io.github.mochiuaena.database;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "sample.error-log-file=target/test-database-errors.jsonl")
class DatabaseLabTest {
    @Autowired TestRestTemplate http;
    @Autowired LabController lab;
    @BeforeEach void reset() { assertThat(post("/lab/reset", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK); }
    @AfterEach void release() { lab.release(); }
    private ResponseEntity<String> post(String path, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Lab", "1");
        return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
    private ObservationStore.Snapshot snapshot() {
        Instant end = Instant.now();
        var value = http.getForObject("/triage/observations?windowMinutes=5&endTime={end}", ObservationStore.Snapshot.class, end);
        assertThat(value.windowEnd()).isEqualTo(end); assertThat(value.windowStart()).isEqualTo(end.minusSeconds(300));
        return value;
    }
    @Test void normalAndEmptyWindowsContainOnlyRealJdbcObservations() {
        assertThat(snapshot().requestCount()).isZero();
        assertThat(http.getForEntity("/api/accounts/1", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var value = snapshot();
        assertThat(value.service()).isEqualTo("account-service"); assertThat(value.schemaVersion()).isEqualTo(2);
        assertThat(value.requestCount()).isEqualTo(1); assertThat(value.synthetic()).isFalse();
        assertThat(value.databasePool().acquisitionTimeoutCount()).isZero(); assertThat(value.errors()).isEmpty();
        assertThat(value.databasePool().queryCount()).isEqualTo(1);
        assertThat(value.databasePool().peakActiveConnections()).isGreaterThanOrEqualTo(1);
        assertThat(value.databasePool().poolSamples()).isPositive();
    }
    @Test void poolExhaustionTimesOutAndClosingHeldConnectionsRestoresRequests() {
        assertThat(post("/lab/scenario", Map.of("scenario", "DB_POOL_EXHAUSTED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/accounts/1", String.class).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var value = snapshot();
        assertThat(value.databasePool().maximumConnections()).isEqualTo(2);
        assertThat(value.databasePool().peakActiveConnections()).isEqualTo(2);
        assertThat(value.databasePool().peakPendingThreads()).isPositive(); assertThat(value.databasePool().exhaustedSamples()).isPositive();
        assertThat(value.databasePool().acquisitionTimeoutCount()).isEqualTo(1);
        assertThat(value.databasePool().queryCount()).isZero(); assertThat(value.databasePool().queryP95Ms()).isZero();
        assertThat(value.databasePool().acquisitionP95Ms()).isGreaterThanOrEqualTo(250);
        assertThat(value.errors()).hasSize(1).allSatisfy(error -> assertThat(error.code()).isEqualTo("DB_CONNECTION_ACQUIRE_TIMEOUT"));
        assertThat(post("/lab/scenario", Map.of("scenario", "NORMAL")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/accounts/1", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(snapshot().requestCount()).isEqualTo(2);
    }
    @Test void rowLockWaitIsAQueryFailureRatherThanAConnectionAcquisitionTimeout() {
        assertThat(post("/lab/scenario", Map.of("scenario", "DB_QUERY_LOCK_WAIT")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/accounts/1", String.class).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        var value = snapshot();
        assertThat(value.databasePool().acquisitionTimeoutCount()).isZero();
        assertThat(value.databasePool().queryErrorCount()).isEqualTo(1);
        assertThat(value.databasePool().queryP95Ms()).isGreaterThanOrEqualTo(500);
        assertThat(value.errors().getFirst().code()).isEqualTo("SQL_QUERY_FAILED");
    }
    @Test void controlsRejectCrossSiteWritesAndInvalidScenarios() {
        assertThat(http.postForEntity("/lab/reset", Map.of(), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("/lab/scenario", Map.of("scenario", "private-unregistered-value")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/lab/scenario", java.util.Collections.singletonMap("scenario", null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Lab", "1"); headers.set("Origin", "https://example.com");
        assertThat(http.postForEntity("/lab/reset", new HttpEntity<>(Map.of(), headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/triage/observations?windowMinutes=61&endTime={end}", String.class, Instant.now()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void abandonedFaultLeaseExpiresAndReturnsTheConnections() {
        assertThat(post("/lab/scenario", Map.of("scenario", "DB_POOL_EXHAUSTED")).getStatusCode()).isEqualTo(HttpStatus.OK);
        await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(400)).until(() ->
            http.getForEntity("/api/accounts/1", String.class).getStatusCode() == HttpStatus.OK);
        assertThat(snapshot().databasePool().acquisitionTimeoutCount()).isPositive();
    }
}
