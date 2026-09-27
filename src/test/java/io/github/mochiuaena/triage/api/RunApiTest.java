package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO",
    "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:http;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}",
    "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}"
})
class RunApiTest {
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    private Map<String, Object> request(String question, String scenario, int minutes) {
        return Map.of("question", question, "service", "order-service", "scenario", scenario, "windowMinutes", minutes);
    }

    private Run completed(String scenario) {
        var response = http.postForEntity("/api/runs", request("订单查询为什么变慢？", scenario, 15), Run.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(5)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        return http.getForObject("/api/runs/" + id, Run.class);
    }

    @Test void bothScenariosCompleteOverRealHttpAndRemainInHistory() {
        for (String scenario : new String[]{"NORMAL", "DOWNSTREAM_TIMEOUT"}) {
            Run run = completed(scenario);
            assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(run.mode()).isEqualTo("DEMO");
            assertThat(run.synthetic()).isTrue();
            assertThat(run.toolCalls()).isEqualTo(3);
            assertThat(http.getForObject("/api/runs?limit=50", RunSummary[].class)).extracting(RunSummary::id).contains(run.id());
        }
    }

    @Test void invalidWindowsQuestionsAndScenariosAreRejectedWithoutEchoingInput() {
        for (var body : java.util.List.of(request("订单", "NORMAL", 0), request("订单", "NORMAL", 61),
            request(" ", "NORMAL", 15), request("x".repeat(201), "NORMAL", 15), request("订单", "INVALID", 15))) {
            assertThat(http.postForEntity("/api/runs", body, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        var secret = Map.of("question", "secret-test-question", "service", "unknown", "scenario", "NORMAL", "windowMinutes", 15);
        var response = http.postForEntity("/api/runs", secret, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).doesNotContain("secret-test-question");
    }

    @Test void missingRunsAndMalformedIdsHaveClearStatuses() {
        assertThat(http.getForEntity("/api/runs/" + UUID.randomUUID(), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/runs/not-a-uuid", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void historyHasEnforcedLimit() {
        assertThat(http.getForEntity("/api/runs?limit=51", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/runs?limit=0", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test void sseReplaysCompletedRunAndResumesAfterLastEventId() throws Exception {
        Run run = completed("DOWNSTREAM_TIMEOUT");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var uri = URI.create("http://127.0.0.1:" + port + "/api/runs/" + run.id() + "/events");
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("id:1", "event:progress", "event:complete", "SUCCEEDED");
            var resumed = client.send(HttpRequest.newBuilder(uri).header("Last-Event-ID", "8")
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resumed.body()).contains("id:9", "event:complete").doesNotContain("id:1\n", "id:8\n");
            var invalid = client.send(HttpRequest.newBuilder(uri).header("Last-Event-ID", "999")
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(invalid.statusCode()).isEqualTo(400);
        }
    }

    @Test void unrelatedQuestionReturnsInsufficientEvidence() {
        var created = http.postForEntity("/api/runs", request("写一首诗", "NORMAL", 15), Run.class).getBody();
        await().atMost(Duration.ofSeconds(3)).until(() -> http.getForObject("/api/runs/" + created.id(), Run.class).status().terminal());
        assertThat(http.getForObject("/api/runs/" + created.id(), Run.class).status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
    }

    @Test void pageAndDemoMetadataAreAvailableWithoutAKey() {
        assertThat(http.getForObject("/", String.class)).contains("mode-label", "app.js", "下游超时");
        assertThat(http.getForObject("/api/demo", String.class)).contains("DEMO", "synthetic", "query_error_logs");
    }
}
