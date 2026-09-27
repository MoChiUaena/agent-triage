package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=MODEL", "triage.model.api-key=test-only-not-a-real-key", "triage.model.name=test-model",
    "triage.model.max-rounds=2", "triage.model.timeout=2s", "triage.run-timeout=8s",
    "spring.datasource.url=jdbc:h2:mem:model-http;DB_CLOSE_DELAY=-1"
})
@ExtendWith(OutputCaptureExtension.class)
class ModelIntegrationTest {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final Stub MODEL = new Stub();
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("triage.model.base-url", MODEL::url);
    }

    @BeforeEach void reset() { MODEL.reset(); }
    @AfterAll static void shutdown() { MODEL.close(); }

    private Run execute() {
        var response = http.postForEntity("/api/runs", Map.of("question", "订单 confidential-test-question 为什么慢？",
            "service", "order-service", "windowMinutes", 15, "scenario", "DOWNSTREAM_TIMEOUT"), Run.class);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(10)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        return http.getForObject("/api/runs/" + id, Run.class);
    }

    @Test void modelChoosesToolOrderAndGetsActualResultsBeforeItsFinalAnswer() throws Exception {
        enqueueAllTools(true);
        MODEL.enqueue(completion(answer("METRICS-ORDER-DOWNSTREAM_TIMEOUT"), true));
        Run run = execute();
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.mode()).isEqualTo("MODEL");
        assertThat(run.synthetic()).isTrue();
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(run.events().stream().filter(e -> e.type().equals("TOOL_STARTED")))
            .extracting(Event::tool).containsExactly("query_error_logs", "read_service_metrics", "search_runbooks");
        assertThat(run.modelExecution().configuredModel()).isEqualTo("test-model");
        assertThat(run.modelExecution().responseModel()).isEqualTo("test-model-v1");
        assertThat(run.modelExecution().calls()).isEqualTo(2);
        assertThat(run.modelExecution().usage()).isEqualTo(new TokenUsage(220, 40, 260));
        assertThat(MODEL.requests).hasSize(2);
        JsonNode first = MODEL.requests.getFirst();
        assertThat(first.get("tools")).hasSize(3);
        assertThat(first.get("model").asText()).isEqualTo("test-model");
        assertThat(first.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(first.get("tools").get(0).at("/function/parameters/properties/service/const").asText()).isEqualTo("order-service");
        assertThat(first.get("tools").get(0).at("/function/parameters/properties/windowMinutes/const").asInt()).isEqualTo(15);
        List<JsonNode> messages = new ArrayList<>();
        MODEL.requests.get(1).get("messages").forEach(messages::add);
        assertThat(messages.stream().filter(m -> m.path("role").asText().equals("tool"))).hasSize(3);
        assertThat(messages.toString()).contains("2350", "SocketTimeoutException", "DOC-DOWNSTREAM-TIMEOUT#v1");
        assertThat(JSON.writeValueAsString(run.events())).doesNotContain("confidential-test-question", "test-only-not-a-real-key");
        assertThat(MODEL.authorization).isEqualTo("Bearer test-only-not-a-real-key");
    }

    @Test void modelCanReturnInsufficientEvidenceWithoutAnyToolsAndMissingUsageStaysNull() throws Exception {
        MODEL.enqueue(completion(insufficient(), false));
        Run run = execute();
        assertThat(run.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(run.toolCalls()).isZero();
        assertThat(run.modelExecution().usage()).isNull();
    }

    @Test void aMissingUsageRoundDoesNotBecomeAnInventedTotal() throws Exception {
        enqueueAllTools(false);
        MODEL.enqueue(completion(answer("METRICS-ORDER-DOWNSTREAM_TIMEOUT"), true));
        Run run = execute();
        assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(run.modelExecution().usage()).isNull();
    }

    @Test void citationsNotCollectedInThisRunAreRejected() throws Exception {
        enqueueAllTools(true);
        MODEL.enqueue(completion(answer("INVENTED-METRIC"), true));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("INVALID_MODEL_OUTPUT");
        assertThat(run.diagnosis()).isNull();
        assertThat(run.evidence()).hasSize(4);
    }

    @Test void aDocumentAloneCannotProveCurrentServiceState() throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "search_runbooks", arguments(true))));
        String id = "DOC-DOWNSTREAM-TIMEOUT#v1";
        var diagnosis = new Diagnosis(List.of(new Finding("服务超时。", List.of(id))), List.of(new Finding("下游超时。", List.of(id))),
            List.of("检查调用。"), "需要更多数据。");
        MODEL.enqueue(completion(JSON.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", diagnosis)), true));
        assertThat(execute().failure().code()).isEqualTo("INVALID_MODEL_OUTPUT");
    }

    @Test void unregisteredToolIsRejected() throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "execute_shell", "{}")));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(run.toolCalls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"service\":\"billing\",\"windowMinutes\":15}",
        "{\"service\":\"order-service\",\"windowMinutes\":60}",
        "{\"service\":\"order-service\",\"windowMinutes\":\"15\"}",
        "{\"service\":\"order-service\",\"windowMinutes\":15.0}",
        "{\"service\":\"order-service\",\"windowMinutes\":15,\"sql\":\"select 1\"}",
        "{\"service\":\"order-service\",\"windowMinutes\":15,\"windowMinutes\":60}",
        "null", "{}"
    })
    void invalidToolArgumentsDoNotReachTheTool(String arguments) throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "read_service_metrics", arguments)));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("INVALID_TOOL_ARGUMENTS");
        assertThat(run.toolCalls()).isZero();
    }

    @Test void repeatedToolParametersStopTheLoop() throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "read_service_metrics", arguments(false))));
        MODEL.enqueue(toolResponse(true, call("two", "read_service_metrics", arguments(false))));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("DUPLICATE_TOOL_CALL");
        assertThat(run.toolCalls()).isEqualTo(1);
        assertThat(MODEL.requests).hasSize(2);
    }

    @Test void roundLimitStopsASequenceOfDifferentQueries() throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "search_runbooks", arguments(true))));
        MODEL.enqueue(toolResponse(true, call("two", "search_runbooks", arguments(true).replace("订单 超时", "订单 正常"))));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("MODEL_ROUND_LIMIT");
        assertThat(MODEL.requests).hasSize(2);
    }

    @Test void toolBudgetAppliesToMultipleCallsInASingleModelReply() throws Exception {
        MODEL.enqueue(toolResponse(true, call("one", "search_runbooks", arguments(true)),
            call("two", "read_service_metrics", arguments(false)), call("three", "query_error_logs", arguments(false)),
            call("four", "search_runbooks", arguments(true).replace("订单 超时", "正常"))));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("TOOL_CALL_LIMIT");
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(MODEL.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{}", "{\"status\":\"FAILED\",\"diagnosis\":null}"})
    void malformedFinalAnswerCannotBeStoredAsSuccess(String answer) throws Exception {
        MODEL.enqueue(completion(answer, true));
        assertThat(execute().failure().code()).isEqualTo("INVALID_MODEL_OUTPUT");
    }

    @Test void providerErrorIsSanitizedAndNotRetried(CapturedOutput output) {
        MODEL.replies.add(new Reply(401, "secret-provider-error-body", 0));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("MODEL_HTTP_ERROR");
        assertThat(MODEL.requests).hasSize(1);
        assertThat(run.toString()).doesNotContain("secret-provider-error-body", "test-only-not-a-real-key");
        assertThat(output.getAll()).doesNotContain("secret-provider-error-body", "test-only-not-a-real-key", "confidential-test-question");
    }

    @Test void emptyProviderResponseDoesNotLogThePrompt(CapturedOutput output) {
        MODEL.enqueue("{\"id\":\"empty\",\"model\":\"test-model-v1\",\"choices\":null}");
        assertThat(execute().failure().code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(output.getAll()).doesNotContain("confidential-test-question");
    }

    @Test void slowModelFailsWithinTheConfiguredWaitBudget() throws Exception {
        MODEL.replies.add(new Reply(200, completion(insufficient(), true), 5000));
        Run run = execute();
        assertThat(run.failure().code()).isEqualTo("MODEL_TIMEOUT");
        assertThat(run.diagnosis()).isNull();
        assertThat(run.modelExecution().usage()).isNull();
        assertThat(MODEL.requests).hasSize(1);
    }

    @Test void oversizedProviderResponseIsBounded() {
        MODEL.enqueue("x".repeat(270_000));
        assertThat(execute().failure().code()).isEqualTo("MODEL_RESPONSE_LIMIT");
    }

    @Test void publicConfigurationDoesNotExposeTheKeyOrEndpoint() {
        String config = http.getForObject("/api/config", String.class);
        assertThat(config).contains("MODEL", "test-model").doesNotContain("test-only-not-a-real-key", MODEL.url());
    }

    @Test void partialProviderUsageRemainsUnknown() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(completion(insufficient(), true));
        ((ObjectNode) response.get("usage")).remove("total_tokens");
        MODEL.enqueue(JSON.writeValueAsString(response));
        assertThat(execute().modelExecution().usage()).isNull();
    }

    @Test void truncatedModelTextIsNotParsedAsACompleteAnswer() throws Exception {
        ObjectNode response = (ObjectNode) JSON.readTree(completion(insufficient(), true));
        ((ObjectNode) response.get("choices").get(0)).put("finish_reason", "length");
        MODEL.enqueue(JSON.writeValueAsString(response));
        assertThat(execute().failure().code()).isEqualTo("MODEL_OUTPUT_TRUNCATED");
    }

    private static String arguments(boolean search) throws Exception {
        Map<String, Object> arguments = new LinkedHashMap<>(Map.of("service", "order-service", "windowMinutes", 15));
        if (search) arguments.put("query", "订单 超时");
        return JSON.writeValueAsString(arguments);
    }

    private static Map<String, Object> call(String id, String name, String arguments) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", arguments));
    }

    private static void enqueueAllTools(boolean usage) throws Exception {
        MODEL.enqueue(toolResponse(usage, call("logs", "query_error_logs", arguments(false)),
            call("metrics", "read_service_metrics", arguments(false)), call("docs", "search_runbooks", arguments(true))));
    }

    @SafeVarargs private static String toolResponse(boolean usage, Map<String, Object>... calls) throws Exception {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant");
        message.putNull("content");
        message.set("tool_calls", JSON.valueToTree(List.of(calls)));
        return response(message, "tool_calls", usage);
    }

    private static String completion(String answer, boolean usage) throws Exception {
        return response(JSON.createObjectNode().put("role", "assistant").put("content", answer), "stop", usage);
    }

    private static String response(ObjectNode message, String finish, boolean usage) throws Exception {
        ObjectNode response = JSON.createObjectNode().put("id", "stub-response").put("created", 1).put("model", "test-model-v1");
        response.putArray("choices").addObject().put("index", 0).put("finish_reason", finish).set("message", message);
        if (usage) response.putObject("usage").put("prompt_tokens", 110).put("completion_tokens", 20).put("total_tokens", 130);
        return JSON.writeValueAsString(response);
    }

    private static String answer(String metricId) throws Exception {
        String logId = "LOGS-ORDER-DOWNSTREAM_TIMEOUT";
        var diagnosis = new Diagnosis(List.of(new Finding("订单 p95 为 2350ms。", List.of(metricId)),
            new Finding("库存调用发生读取超时。", List.of(logId))),
            List.of(new Finding("库存调用超时可能拖慢订单查询。", List.of(metricId, logId, "DOC-DOWNSTREAM-TIMEOUT#v1"))),
            List.of("检查同一窗口内库存服务的处理耗时。"), "缺少网络和数据库指标。");
        return JSON.writeValueAsString(Map.of("status", "SUCCEEDED", "diagnosis", diagnosis));
    }

    private static String insufficient() throws Exception {
        return JSON.writeValueAsString(Map.of("status", "INSUFFICIENT_EVIDENCE", "diagnosis",
            new Diagnosis(List.of(), List.of(), List.of("提供订单服务的具体问题。"), "没有相关观测。")));
    }

    private record Reply(int status, String body, long delayMs) {}

    private static final class Stub implements AutoCloseable {
        final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());
        volatile String authorization;

        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        authorization = exchange.getRequestHeaders().getFirst("Authorization");
                        requests.add(JSON.readTree(exchange.getRequestBody()));
                        Reply reply = replies.poll();
                        if (reply == null) reply = new Reply(500, "Unscripted request", 0);
                        if (reply.delayMs() > 0) {
                            try { Thread.sleep(reply.delayMs()); }
                            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                        }
                        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(reply.status(), bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (IOException ignored) { /* A cancelled model request closes its socket. */ }
                });
                server.start();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        void enqueue(String body) { replies.add(new Reply(200, body, 0)); }
        void reset() { replies.clear(); requests.clear(); authorization = null; }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
