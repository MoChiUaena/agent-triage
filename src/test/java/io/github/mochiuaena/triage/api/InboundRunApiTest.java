package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.settings.*;
import io.github.mochiuaena.triage.source.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={"triage.mode=DEMO", "triage.observation.source=LIVE",
    "spring.datasource.url=jdbc:h2:mem:inbound-api;DB_CLOSE_DELAY=-1", "triage.settings.key-file=target/inbound-api-key"})
class InboundRunApiTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Stub STUB = new Stub();
    @Autowired TestRestTemplate http;
    @Autowired ProviderRegistry providers;
    @Autowired SourceProjectService sources;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path root;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("triage.services[0].id", () -> "entry-service"); values.add("triage.services[0].name", () -> "入口服务");
        values.add("triage.services[0].base-url", STUB::url); values.add("triage.services[0].protocol", () -> "HTTP_REQUESTS_V4");
    }
    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM source_projects"); jdbc.update("DELETE FROM model_selection"); jdbc.update("DELETE FROM model_providers");
        STUB.requests.clear(); STUB.split = false; STUB.classification = false; STUB.execution = true;
        String source = "package fixture;\nclass EntranceController {\n@GetMapping(\"/api/entrance/{id}\") Object get(String id) { return null; }\n}\n";
        Files.writeString(root.resolve("EntranceController.java"), source);
        STUB.hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        sources.create("入站接口验证", "entry-service", root.toString());
    }
    @AfterAll static void close() { STUB.server.stop(0); }
    private Run run(boolean source) {
        return run(source, Status.INSUFFICIENT_EVIDENCE);
    }
    private Run run(boolean source, Status expected) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Source", "1");
        var response = http.postForEntity("/api/runs", new HttpEntity<>(Map.of("question", "入口服务为什么返回 500？", "service", "entry-service",
            "windowMinutes", 1, "endpointId", STUB.endpoint.id(), "includeSource", source), headers), Run.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(8)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        var value = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(value.status()).isEqualTo(expected);
        if (expected == Status.INSUFFICIENT_EVIDENCE) {
            assertThat(value.diagnosis().possibleCauses()).isEmpty(); assertThat(value.diagnosis().uncertainty()).contains("未采集下游");
        } else assertThat(value.diagnosis().possibleCauses().getFirst().text()).contains("请求执行", "异常");
        var metrics = value.evidence().stream().filter(item -> item.source().equals("read_service_metrics")).findFirst().orElseThrow();
        assertThat(metrics.data()).containsEntry("observationType", "HTTP_REQUESTS").doesNotContainKeys("timeoutCount", "downstreamTimeoutRate", "downstreamP95Ms");
        assertThat(value.serviceInfo().downstreamId()).isNull();
        assertThat(http.getForObject("/api/runs/" + id, Run.class)).isEqualTo(value);
        return value;
    }
    private void model() {
        var provider = providers.create(new ProviderConfig.Input("入站协议验证", ProviderConfig.Protocol.OPENAI_COMPATIBLE,
            STUB.url(), "inbound-stub", "test-only-local", .2, 3, 4, 1600, 0));
        providers.select("MODEL", provider.id());
    }
    @Test void endpointSourceMappingAndStoredHistoryPreserveUnknownDownstreamCounters() {
        var config = http.getForObject("/api/config", JsonNode.class);
        assertThat(config.path("endpointSupported").asBoolean()).isTrue();
        assertThat(config.path("observationAvailable").asBoolean()).isTrue();
        var result = run(true);
        var matches = result.sourceAnalysis().graph().endpointMatches();
        assertThat(matches).hasSize(1); assertThat(matches.getFirst().state()).isEqualTo("MATCHED");
        assertThat(matches.getFirst().timeoutCount()).isNull();
    }
    @Test void modelCollectsRequestEvidenceButDoesNotGenerateAFinalDownstreamAnswer() {
        model(); var result = run(false);
        assertThat(result.events()).extracting(Event::type).contains("INBOUND_OBSERVATION_GATE").doesNotContain("CONCLUSION_RENDERED");
        assertThat(STUB.requests).hasSize(1);
        assertThat(STUB.requests.toString()).doesNotContain("下游：null", "inventory-service");
    }
    @Test void multiRoundToolSelectionKeepsTheInboundRuleAndHidesLocalRoutingMetadata() {
        model(); STUB.split = true; run(false);
        assertThat(STUB.requests).hasSize(2);
        assertThat(STUB.requests.get(1).path("tool_choice").asText()).isEqualTo("auto");
        assertThat(STUB.requests.get(1).path("messages").toString()).doesNotContain("DOWNSTREAM_TIMEOUT_OBSERVED_rule", "NO_DOWNSTREAM_TIMEOUT_OBSERVED_rule");
        assertThat(STUB.requests.get(1).path("messages").toString()).doesNotContain("fixture.EntranceController", "/api/entrance/{id}", "MVC_SELECTED", "requestDetails");
    }
    @Test void classifiedExecutionFailureSucceedsInBothModesAndStoredHistoryKeepsItsEvidence() {
        STUB.classification = true;
        run(true, Status.SUCCEEDED);
        model(); var modeled = run(false, Status.SUCCEEDED);
        assertThat(modeled.modelExecution().assessment()).isEqualTo("REQUEST_EXECUTION_FAILURE_OBSERVED");
        assertThat(STUB.requests).hasSize(2);
        assertThat(modeled.events()).extracting(Event::type).contains("CONCLUSION_RENDERED").doesNotContain("INBOUND_OBSERVATION_GATE");
    }
    @Test void aPlainServerErrorStillRemainsInsufficientEvenWithClassificationEnabled() {
        STUB.classification = true; STUB.execution = false;
        run(false); model(); run(false);
        assertThat(STUB.requests).hasSize(1);
    }
    private static final class Stub {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final RequestEndpoint endpoint;
        volatile String hash;
        volatile boolean split;
        volatile boolean classification, execution = true;
        Stub() {
            try {
                String identity = String.join("\0", "GET", "/api/entrance/{id}", "fixture.EntranceController", "get", "java.lang.String");
                String id = "EP-" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
                endpoint = new RequestEndpoint(id, "GET", "/api/entrance/{id}", "fixture.EntranceController", "get", List.of("java.lang.String"), "MVC_SELECTED");
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/triage/request-observations", exchange -> {
                    try (exchange) {
                        var params = new HashMap<String, String>();
                        for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                            String[] parts = pair.split("=", 2); params.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                        }
                        Instant end = Instant.parse(params.get("endTime"));
                        var observed = new RequestEndpoint(endpoint.id(), endpoint.httpMethod(), endpoint.routeTemplate(), endpoint.handlerClass(), endpoint.handlerMethod(), endpoint.parameterTypes(), endpoint.stage(), hash);
                        var data = JSON.createObjectNode().put("schemaVersion", 4).put("kind", "HTTP_REQUESTS").put("service", "entry-service")
                            .put("windowStart", end.minusSeconds(Long.parseLong(params.get("windowMinutes")) * 60).toString()).put("windowEnd", end.toString())
                            .put("requestCount", 2).put("recordedRequestCount", 2).put("requestP95Ms", 20).putNull("baselineRequestP95Ms")
                            .put("synthetic", false).put("unattributedRequestCount", 0).put("otherEndpointRequestCount", 0);
                        data.set("endpoint", params.containsKey("endpointId") ? JSON.valueToTree(observed) : JSON.nullNode());
                        var statuses = JSON.createObjectNode().put("informational", 0).put("successful", 1).put("redirection", 0)
                            .put("clientError", 0).put("serverError", 1).put("unknown", 0);
                        data.set("responseStatuses", statuses);
                        var summary = data.putArray("endpoints").addObject().put("requestCount", 2).put("requestP95Ms", 20);
                        summary.set("endpoint", JSON.valueToTree(observed)); summary.set("responseStatuses", statuses);
                        var error = data.putArray("errors").addObject().put("timestamp", end.toString()).put("traceId", "fixture").put("level", "ERROR").put("message", "HTTP request failed");
                        if (classification) {
                            var counts = JSON.createObjectNode().put("executionFailures", execution ? 1 : 0).put("serverErrorResponses", execution ? 0 : 1)
                                .put("asyncTimeouts", 0).put("asyncErrors", 0).put("handledExceptions", 0);
                            data.set("requestFailures", counts); summary.set("requestFailures", counts.deepCopy());
                            error.put("code", execution ? "REQUEST_EXECUTION_FAILED" : "HTTP_SERVER_ERROR_RESPONSE").put("responseClass", 5);
                        }
                        send(exchange, data);
                    }
                });
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        var input = JSON.readTree(exchange.getRequestBody()); requests.add(input);
                        if (classification && execution && requests.size() > 1) {
                            var ids = new ArrayList<String>();
                            for (var message : input.path("messages")) if (message.path("role").asText().equals("tool"))
                                for (var item : JSON.readTree(message.path("content").asText()))
                                    if (item.path("source").asText().equals("read_service_metrics") || item.path("source").asText().equals("query_error_logs")
                                        || item.path("id").asText().startsWith("DOC-REQUEST-EXECUTION-FAILURE#")) ids.add(item.path("id").asText());
                            send(exchange, JSON.valueToTree(Map.of("id", "inbound-stub", "created", 1, "model", "inbound-stub",
                                "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content",
                                    JSON.writeValueAsString(Map.of("assessment", "REQUEST_EXECUTION_FAILURE_OBSERVED", "evidenceIds", ids, "nextChecks", List.of("CORRELATE_TRACE")))))),
                                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15))));
                            return;
                        }
                        List<String> names = split ? requests.size() == 1 ? List.of("search_runbooks") : List.of("read_service_metrics", "query_error_logs")
                            : List.of("read_service_metrics", "query_error_logs", "search_runbooks");
                        var calls = new ArrayList<Map<String, Object>>();
                        for (String name : names) {
                            var args = new HashMap<String, Object>(Map.of("service", "entry-service", "windowMinutes", 1));
                            if (name.equals("search_runbooks")) args.put("query", "请求 失败");
                            calls.add(Map.of("id", name, "type", "function", "function", Map.of("name", name, "arguments", JSON.writeValueAsString(args))));
                        }
                        send(exchange, JSON.valueToTree(Map.of("id", "inbound-stub", "created", 1, "model", "inbound-stub",
                            "choices", List.of(Map.of("index", 0, "finish_reason", "tool_calls", "message", Map.of("role", "assistant", "tool_calls", calls))),
                            "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15))));
                    }
                }); server.start();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        static void send(com.sun.net.httpserver.HttpExchange exchange, JsonNode body) throws java.io.IOException {
            byte[] bytes = JSON.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
}
