package io.github.mochiuaena.triage.source;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.settings.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=jdbc:h2:mem:source-model;DB_CLOSE_DELAY=-1",
    "triage.run-timeout=15s", "triage.settings.key-file=target/source-model-key"
})
class SourceIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Stub MODEL = new Stub();
    @Autowired TestRestTemplate http;
    @Autowired SourceProjectService sources;
    @Autowired SourceProjectRepository projects;
    @Autowired ProviderRegistry providers;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    SourceModels.View project;
    ProviderConfig.View provider;
    Path file;

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM source_projects");
        jdbc.update("DELETE FROM model_selection");
        jdbc.update("DELETE FROM model_providers");
        MODEL.requests.clear(); MODEL.beforeFinal = () -> {}; MODEL.beforeSourceReply = () -> {}; MODEL.forge = false; MODEL.sourceDelay = 0;
        provider = providers.create(input(4, 0)); providers.select("MODEL", provider.id());
        file = root.resolve("TicketController.java");
        Files.writeString(file, """
            @RestController
            class TicketController {
                // private-source-marker: never sent unless both permissions are enabled
                @GetMapping("/api/tickets/{id}")
                Object ticket(String id) {
                    return assignmentClient.get().retrieve().body(Object.class);
                }
            }
            """);
        project = sources.create("独立工单项目", "order-service", root.toString());
    }
    @AfterAll static void close() { MODEL.close(); }
    private ProviderConfig.Input input(int rounds, long version) {
        return new ProviderConfig.Input("源码协议测试", ProviderConfig.Protocol.OPENAI_COMPATIBLE, MODEL.url(), "source-test-model",
            "test-only-local", .2, 1, rounds, 1600, version);
    }
    private void share() {
        var selected = providers.current();
        project = sources.sharing(project.id(), project.revision(), true, provider.id(), provider.version(), selected.selectionToken());
        assertThat(project.sharingActive()).isTrue();
    }
    private Run queued(boolean include, boolean allowModel) {
        return queued("订单请求慢，检查 ticket 方法", include, allowModel);
    }
    private Run queued(String question, boolean include, boolean allowModel) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Source", "1");
        var input = Map.of("question", question, "service", "order-service", "windowMinutes", 15,
            "scenario", "DOWNSTREAM_TIMEOUT", "includeSource", include, "allowSourceModel", allowModel,
            "expectedSelection", providers.current().selectionToken(), "expectedSourceRevision", project.revision());
        var created = http.postForEntity("/api/runs", new HttpEntity<>(input, headers), Run.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return created.getBody();
    }
    private Run run(boolean include, boolean allowModel) {
        UUID id = queued(include, allowModel).id();
        await().atMost(Duration.ofSeconds(12)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        Run result = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(result.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(result.diagnosis()).isNotNull();
        assertThat(result.modelExecution().assessment()).isEqualTo("DOWNSTREAM_TIMEOUT_OBSERVED");
        return result;
    }
    @Test void defaultLocalSearchNeverAddsPrivateSourceToRuntimeModelMessages() {
        Run result = run(true, false);
        assertThat(result.sourceAnalysis().state()).isEqualTo("LOCAL");
        assertThat(result.sourceAnalysis().excerpts()).isNotEmpty();
        assertThat(result.sourceAnalysis().modelUsed()).isFalse();
        assertThat(MODEL.requests).hasSize(2);
        assertThat(MODEL.requests.toString()).doesNotContain("private-source-marker", "TicketController.java", root.toString());
    }
    @Test void projectConsentAloneDoesNotSendSourceAndOldRunsHaveNoSourceAnalysis() {
        share();
        assertThat(run(true, false).sourceAnalysis().state()).isEqualTo("LOCAL");
        assertThat(run(false, false).sourceAnalysis()).isNull();
        assertThat(MODEL.requests.toString()).doesNotContain("private-source-marker");
    }
    @Test void explicitProjectAndRunPermissionsSendOnlyVerifiedCandidatesAndKeepUsageAndDiagnosis() {
        share(); Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("MODEL_SELECTED");
        assertThat(result.sourceAnalysis().modelUsed()).isTrue();
        assertThat(result.sourceAnalysis().excerpts()).hasSize(1);
        var excerpt = result.sourceAnalysis().excerpts().getFirst();
        assertThat(excerpt.path()).isEqualTo("TicketController.java");
        assertThat(sources.excerpt(project.id(), excerpt.id())).isEqualTo(excerpt);
        assertThat(MODEL.requests).hasSize(3);
        assertThat(MODEL.requests.getLast().toString()).contains("private-source-marker").doesNotContain(root.toString());
        assertThat(result.modelExecution().calls()).isEqualTo(3);
        assertThat(result.modelExecution().usage()).isEqualTo(new TokenUsage(30, 15, 45));
        assertThat(result.modelExecution().requestedNextChecks()).isNotEmpty();
        var headers = new HttpHeaders(); headers.set("Origin", "https://foreign.example");
        assertThat(http.exchange("/api/runs/" + result.id(), HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/api/runs/" + result.id() + "/events", HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    @Test void fabricatedSourceIdsAreNotAcceptedAndRuntimeResultSurvives() {
        share(); MODEL.forge = true;
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("MODEL_REJECTED");
        assertThat(result.sourceAnalysis().excerpts()).noneMatch(value -> value.id().equals("SRC-invented"));
        assertThat(result.failure()).isNull();
    }
    @ParameterizedTest @ValueSource(strings = {"revoke", "reindex", "provider-change", "provider-update", "file-change", "unbind", "delete"})
    void changesBeforeSourceDispatchPreventPrivateCodeTransmission(String change) {
        share();
        MODEL.beforeFinal = () -> {
            switch (change) {
                case "revoke" -> sources.sharing(project.id(), project.revision(), false, null, null, null);
                case "reindex" -> sources.reindex(project.id());
                case "provider-change" -> providers.select("DEMO", null);
                case "provider-update" -> providers.update(provider.id(), input(4, provider.version()));
                case "unbind" -> sources.update(project.id(), project.revision(), project.name(), null, project.root());
                case "delete" -> sources.delete(project.id(), project.revision());
                case "file-change" -> { try { Files.writeString(file, "class Changed {}"); } catch (Exception e) { throw new AssertionError(e); } }
            }
        };
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isIn("LOCAL", "STALE");
        assertThat(MODEL.requests).hasSize(2);
        assertThat(MODEL.requests.toString()).doesNotContain("private-source-marker");
    }
    @Test void optionalSourceRoundRespectsConfiguredRoundLimit() {
        provider = providers.update(provider.id(), input(2, provider.version())); share();
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("LOCAL");
        assertThat(result.modelExecution().calls()).isEqualTo(2);
        assertThat(MODEL.requests.toString()).doesNotContain("private-source-marker");
    }
    @Test void sourceTimeoutPreservesRuntimeResult() {
        share(); MODEL.sourceDelay = 1700;
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(result.modelExecution().calls()).isEqualTo(3);
        assertThat(result.modelExecution().usage()).isNull();
    }
    @Test void missingOrStaleConsentIsRejectedBeforeCreatingARun() {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Source", "1");
        Map<String,Object> input = new HashMap<>(Map.of("question", "订单为什么慢？", "service", "order-service", "windowMinutes", 15,
            "scenario", "DOWNSTREAM_TIMEOUT", "includeSource", true, "allowSourceModel", true));
        assertThat(http.postForEntity("/api/runs", new HttpEntity<>(input, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        share(); input.put("expectedSourceRevision", 999);
        assertThat(http.postForEntity("/api/runs", new HttpEntity<>(input, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity("/api/runs", input, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MODEL.requests).isEmpty();
    }
    @Test void cancellingSourceRoundRecordsDispatchAndDoesNotPublishALateSelection() {
        share(); MODEL.sourceDelay = 850;
        Run submitted = queued(true, true);
        await().atMost(Duration.ofSeconds(5)).until(() -> MODEL.requests.size() == 3);
        var headers = new HttpHeaders(); headers.set("X-Triage-Run", "1");
        Run cancelled = http.postForObject("/api/runs/" + submitted.id() + "/cancel", new HttpEntity<>(Map.of(), headers), Run.class);
        assertThat(cancelled.status()).isEqualTo(Status.CANCELLED);
        assertThat(cancelled.sourceAnalysis().modelUsed()).isTrue();
        assertThat(cancelled.sourceAnalysis().state()).isEqualTo("MODEL_PENDING");
        assertThat(cancelled.events()).extracting(Event::type).contains("SOURCE_MODEL_DISPATCH").doesNotContain("SOURCE_COMPLETED", "RUN_COMPLETED");
        assertThat(cancelled.modelExecution().calls()).isEqualTo(3);
        assertThat(cancelled.modelExecution().completedCalls()).isEqualTo(2);
        assertThat(http.getForObject("/api/runs/" + submitted.id(), Run.class)).isEqualTo(cancelled);
    }
    @Test void expandingLocalCallGraphDoesNotExpandTheAuthorizedModelPayload() throws Exception {
        Files.writeString(file, "class TicketController { Leaf leaf; @GetMapping(\"/tickets\") Object ticket(String id) { return leaf.open(id); } }");
        Files.writeString(root.resolve("Leaf.java"), """
            class Leaf {
                Object open(String id) {
                    // graph-only-private-fixture
                    return null;
                }
            }
            """);
        project = sources.reindex(project.id()); share();
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().graph().nodes()).anyMatch(value -> value.excerpt().content().contains("graph-only-private-fixture"));
        assertThat(MODEL.requests).hasSize(3);
        assertThat(MODEL.requests.toString()).doesNotContain("graph-only-private-fixture");
    }
    @Test void sourceSelectionDoesNotBypassTheOutOfScopeModelGate() {
        share(); Run submitted = queued("写一首 ticket 的诗", true, true);
        await().atMost(Duration.ofSeconds(5)).until(() -> http.getForObject("/api/runs/" + submitted.id(), Run.class).status().terminal());
        Run result = http.getForObject("/api/runs/" + submitted.id(), Run.class);
        assertThat(result.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.sourceAnalysis().modelUsed()).isFalse();
        assertThat(result.modelExecution().calls()).isZero();
        assertThat(MODEL.requests).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"delete", "file-change"})
    void sourceChangesAfterDispatchRetainTheFactThatCodeWasSent(String change) {
        share();
        MODEL.beforeSourceReply = () -> {
            if (change.equals("delete")) sources.delete(project.id(), project.revision());
            else try { Files.writeString(file, "class Changed {}"); } catch (Exception e) { throw new AssertionError(e); }
        };
        Run result = run(true, true);
        assertThat(result.sourceAnalysis().state()).isEqualTo("STALE");
        assertThat(result.sourceAnalysis().modelUsed()).isTrue();
        assertThat(result.sourceAnalysis().graph()).isNotNull();
        assertThat(MODEL.requests).hasSize(3);
    }
    static class Stub implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile Runnable beforeFinal = () -> {};
        volatile Runnable beforeSourceReply = () -> {};
        volatile boolean forge;
        volatile long sourceDelay;
        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        JsonNode request = JSON.readTree(exchange.getRequestBody()); requests.add(request);
                        Map<String,Object> message;
                        String finish;
                        if (request.path("messages").get(0).path("content").asText().contains("SOURCE_SELECTION")) {
                            Runnable sourceHook = beforeSourceReply; long delay = sourceDelay;
                            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                            sourceHook.run();
                            var payload = JSON.readTree(request.path("messages").get(1).path("content").asText());
                            String id = forge ? "SRC-invented" : payload.path("candidates").get(0).path("id").asText();
                            message = Map.of("role", "assistant", "content", JSON.writeValueAsString(Map.of("sourceIds", List.of(id)))); finish = "stop";
                        } else if (request.path("tool_choice").asText().equals("none")) {
                            beforeFinal.run();
                            String answer = JSON.writeValueAsString(Map.of("assessment", "DOWNSTREAM_TIMEOUT_OBSERVED", "evidenceIds",
                                List.of("METRICS-ORDER-DOWNSTREAM_TIMEOUT", "LOGS-ORDER-DOWNSTREAM_TIMEOUT", "DOC-DOWNSTREAM-TIMEOUT#v1"), "nextChecks", List.of("CORRELATE_TRACE")));
                            message = Map.of("role", "assistant", "content", answer); finish = "stop";
                        } else {
                            var calls = new ArrayList<Map<String,Object>>();
                            for (String tool : List.of("read_service_metrics", "query_error_logs", "search_runbooks")) {
                                var args = new HashMap<String,Object>(Map.of("service", "order-service", "windowMinutes", 15));
                                if (tool.equals("search_runbooks")) args.put("query", "正常 超时");
                                calls.add(Map.of("id", tool, "type", "function", "function", Map.of("name", tool, "arguments", JSON.writeValueAsString(args))));
                            }
                            message = Map.of("role", "assistant", "tool_calls", calls); finish = "tool_calls";
                        }
                        byte[] response = JSON.writeValueAsBytes(Map.of("id", "source-stub", "model", "source-test-model", "created", 1,
                            "choices", List.of(Map.of("index", 0, "finish_reason", finish, "message", message)),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
                        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                    } catch (Exception ignored) { }
                }); server.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
