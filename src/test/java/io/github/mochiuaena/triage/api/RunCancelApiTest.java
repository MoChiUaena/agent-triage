package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.tools.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "triage.tool-timeout=5s", "spring.datasource.url=jdbc:h2:mem:cancel-api;DB_CLOSE_DELAY=-1"
})
class RunCancelApiTest {
    @Autowired TestRestTemplate http;
    @Autowired RunEventController events;
    @LocalServerPort int port;
    private static CountDownLatch entered, release;
    @BeforeEach void latches() { entered = new CountDownLatch(1); release = new CountDownLatch(1); }
    @AfterEach void cleanup() { release.countDown(); }
    @TestConfiguration static class ControlledExecution {
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor enginePriority() {
            return beans -> beans.getBeanDefinition("engineRouter").setPrimary(false);
        }
        @Bean @Primary TriageEngine cancellableEngine() {
            return new TriageEngine() {
                public String mode() { return "DEMO"; }
                public Decision investigate(io.github.mochiuaena.triage.execution.ExecutionSession session) {
                    session.callTool("cancel_test", "");
                    return new Decision(Status.INSUFFICIENT_EVIDENCE, new Diagnosis(List.of(), List.of(), List.of("补充证据。"), "证据不足。"));
                }
            };
        }
        @Bean ReadOnlyTool cancellableTool() {
            return new ReadOnlyTool() {
                public String name() { return "cancel_test"; }
                public List<Evidence> execute(ToolContext context, String query) {
                    entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return List.of();
                }
            };
        }
    }
    private Run create() throws Exception {
        var response = http.postForEntity("/api/runs", Map.of("question", "订单为何变慢", "service", "order-service", "windowMinutes", 5, "scenario", "NORMAL"), Run.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); return response.getBody();
    }
    private HttpEntity<String> cancelHeaders(String origin) {
        var headers = new HttpHeaders(); headers.set("X-Triage-Run", "1"); if (origin != null) headers.set("Origin", origin);
        return new HttpEntity<>("", headers);
    }
    @Test void cancellationIsPersistedIdempotentAndCompletesSse() throws Exception {
        Run run = create(); String path = "/api/runs/" + run.id() + "/cancel";
        var cancelled = http.postForEntity(path, cancelHeaders(null), Run.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK); assertThat(cancelled.getBody().status()).isEqualTo(Status.CANCELLED);
        assertThat(http.getForObject("/api/runs/" + run.id(), Run.class)).isEqualTo(cancelled.getBody());
        assertThat(http.postForObject(path, cancelHeaders(null), Run.class)).isEqualTo(cancelled.getBody());
        try (var client = HttpClient.newHttpClient()) {
            var stream = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs/" + run.id() + "/events"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(stream.body()).contains("RUN_CANCELLED", "event:complete", "CANCELLED").doesNotContain("RUN_FAILED");
        }
    }
    @Test void missingHeaderAndCrossOriginRequestsCannotCancel() throws Exception {
        Run run = create(); String path = "/api/runs/" + run.id() + "/cancel";
        assertThat(http.postForEntity(path, "", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.postForEntity(path, cancelHeaders("https://example.com"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.getForObject("/api/runs/" + run.id(), Run.class).status()).isEqualTo(Status.RUNNING);
        assertThat(http.postForEntity(path, cancelHeaders(null), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void repeatedCancelledEventStreamsReturnTheirConnectionPermitsAndScheduledTasks() throws Exception {
        Run run = create();
        var cancelled = http.postForEntity("/api/runs/" + run.id() + "/cancel", cancelHeaders(null), Run.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        var scheduler = (ScheduledThreadPoolExecutor) org.springframework.test.util.ReflectionTestUtils.getField(events, "scheduler");
        var permits = (Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(events, "connections");
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> scheduler.getQueue().isEmpty());
        int initialPermits = permits.availablePermits();
        try (var client = HttpClient.newHttpClient()) {
            for (int i = 0; i < 80; i++) {
                var stream = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs/" + run.id() + "/events"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(stream.statusCode()).isEqualTo(200);
                assertThat(stream.body()).contains("RUN_CANCELLED", "event:complete");
            }
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(permits.availablePermits()).isEqualTo(initialPermits);
            assertThat(scheduler.getQueue()).isEmpty();
        });
    }
    @Test void unknownAndMalformedIdsReturnClearStatuses() {
        assertThat(http.postForEntity("/api/runs/" + UUID.randomUUID() + "/cancel", cancelHeaders(null), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/runs/not-an-id/cancel", cancelHeaders(null), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
