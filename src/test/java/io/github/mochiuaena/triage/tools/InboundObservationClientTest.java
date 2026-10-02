package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class InboundObservationClientTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant end = Instant.parse("2026-10-02T00:00:00Z");
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private HttpServer server;
    private ServiceRegistry registry;
    private LiveObservationClient client;
    private ToolContext context;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/triage/request-observations", exchange -> {
            exchange.sendResponseHeaders(200, body.get().length);
            try (var out = exchange.getResponseBody()) { out.write(body.get()); }
        });
        server.start();
        var env = new MockEnvironment().withProperty("triage.services[0].id", "inbound-service")
            .withProperty("triage.services[0].name", "入站服务")
            .withProperty("triage.services[0].protocol", "HTTP_REQUESTS_V4")
            .withProperty("triage.services[0].base-url", "http://127.0.0.1:" + server.getAddress().getPort());
        registry = new ServiceRegistry(new ObservationSource("LIVE", "http://127.0.0.1:18082"), env);
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("inbound-service", 1, Scenario.OBSERVED, end, registry.defaultTarget());
        body.set(json.writeValueAsBytes(valid()));
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    private RequestEndpoint endpoint() throws Exception {
        String identity = String.join("\0", "GET", "/api/items/{id}", "example.Controller", "item", "java.lang.String");
        String id = "EP-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        return new RequestEndpoint(id, "GET", "/api/items/{id}", "example.Controller", "item", List.of("java.lang.String"), "MVC_SELECTED");
    }
    private ObjectNode counts() {
        return json.createObjectNode().put("informational", 0).put("successful", 1).put("redirection", 0)
            .put("clientError", 0).put("serverError", 1).put("unknown", 0);
    }
    private ObjectNode valid() throws Exception {
        var value = json.createObjectNode().put("schemaVersion", 4).put("kind", "HTTP_REQUESTS").put("service", "inbound-service")
            .put("windowStart", end.minusSeconds(60).toString()).put("windowEnd", end.toString()).put("requestCount", 2)
            .put("recordedRequestCount", 2).put("requestP95Ms", 20).putNull("baselineRequestP95Ms").put("synthetic", false)
            .putNull("endpoint").put("unattributedRequestCount", 0).put("otherEndpointRequestCount", 0);
        value.set("responseStatuses", counts());
        var summary = value.putArray("endpoints").addObject().put("requestCount", 2).put("requestP95Ms", 20);
        summary.set("endpoint", json.valueToTree(endpoint())); summary.set("responseStatuses", counts());
        value.putArray("errors").addObject().put("timestamp", end.toString()).put("traceId", "fixture")
            .put("level", "ERROR").put("message", "HTTP request failed");
        return value;
    }
    @Test void requestOnlyServiceNeedsNoDownstreamAndItsEvidenceNeverInventsZeroMetrics() {
        assertThat(registry.defaultTarget().info().downstreamId()).isNull();
        var metrics = new LiveMetricsTool(client).execute(context, "").getFirst();
        var logs = new LiveErrorLogsTool(client).execute(context, "").getFirst();
        assertThat(metrics.data()).containsEntry("observationType", "HTTP_REQUESTS").containsEntry("requestCount", 2)
            .doesNotContainKeys("timeoutCount", "downstreamP95Ms", "downstreamTimeoutRate");
        assertThat(logs.data()).containsEntry("observationType", "HTTP_REQUESTS").doesNotContainKey("timeoutCount");
        assertThat(metrics.summary()).contains("未采集下游").doesNotContain("超时 0 次", "0%");
        assertThat(logs.summary()).doesNotContain("没有下游请求超时");
    }
    @Test void endpointMetadataKeepsMissingDownstreamCountersAndStillSupportsSelection() throws Exception {
        var all = client.snapshot(context);
        assertThat(all.requestDetails().endpoints().getFirst().timeoutCount()).isNull();
        assertThat(all.requestDetails().endpoints().getFirst().downstreamP95Ms()).isNull();
        var value = valid(); value.set("endpoint", json.valueToTree(endpoint())); body.set(json.writeValueAsBytes(value));
        var selected = new ToolContext(context.service(), 1, Scenario.OBSERVED, end, registry.defaultTarget(), endpoint());
        assertThat(client.snapshot(selected).requestDetails().endpoint()).isEqualTo(endpoint());
    }
    @Test void fakeDownstreamFieldsAndInconsistentResponseTotalsAreRejected() throws Exception {
        for (String field : List.of("timeoutCount", "downstreamP95Ms", "downstreamTimeoutRate", "downstreamService")) {
            var value = valid(); value.put(field, 0); body.set(json.writeValueAsBytes(value));
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(ObservationFailure.class);
        }
        var value = valid(); ((ObjectNode) value.path("responseStatuses")).put("successful", 2); body.set(json.writeValueAsBytes(value));
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(ObservationFailure.class);
    }
    @Test void inboundWindowNeverBecomesANoDownstreamTimeoutDiagnosis() throws Exception {
        var value = valid(); value.putArray("errors"); body.set(json.writeValueAsBytes(value));
        var metrics = new LiveMetricsTool(client).execute(context, "").getFirst();
        var logs = new LiveErrorLogsTool(client).execute(context, "").getFirst();
        var diagnosis = new io.github.mochiuaena.triage.execution.DemoReasoner().diagnose(List.of(metrics, logs), context.serviceInfo());
        assertThat(diagnosis.possibleCauses()).isEmpty(); assertThat(diagnosis.uncertainty()).contains("未采集下游");
        assertThat(io.github.mochiuaena.triage.execution.QuestionScope.supports("返回 404 是什么原因", context)).isTrue();
    }
}
