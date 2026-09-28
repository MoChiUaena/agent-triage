package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class LiveObservationClientTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant end = Instant.parse("2026-09-28T00:00:00Z");
    private final AtomicReference<byte[]> response = new AtomicReference<>();
    private HttpServer server;
    private ServiceRegistry registry;
    private LiveObservationClient client;
    private ToolContext context;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/triage/observations", exchange -> {
            byte[] body = response.get();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        registry = new ServiceRegistry(new ObservationSource("LIVE", origin), List.of(new ServiceRegistry.Config(
            "checkout-service", "结算服务", "stock-service", "商品服务", origin, ServiceRegistry.Protocol.OBSERVATIONS_V1, 15, false)));
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("checkout-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
        response.set(json.writeValueAsBytes(valid()));
    }
    @AfterEach void stop() { server.stop(0); }

    private ObjectNode databaseResponse() {
        var node = json.createObjectNode();
        node.put("schemaVersion", 2).put("kind", "DATABASE_POOL").put("service", "account-service").put("database", "accounts-db")
            .put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString()).put("requestCount", 5)
            .put("recordedRequestCount", 5).put("requestP95Ms", 5).putNull("baselineRequestP95Ms").put("synthetic", false);
        node.putArray("errors");
        node.putObject("databasePool").put("maximumConnections", 2).put("peakActiveConnections", 1).put("peakPendingThreads", 0)
            .put("poolSamples", 20).put("exhaustedSamples", 0).put("acquisitionTimeoutCount", 0).put("acquisitionErrorCount", 0)
            .put("queryCount", 5).put("queryErrorCount", 0).put("acquisitionP95Ms", 1).put("queryP95Ms", 3);
        return node;
    }
    private void databaseClient() {
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        registry = new ServiceRegistry(new ObservationSource("LIVE", origin), List.of(new ServiceRegistry.Config(
            "account-service", "账户服务", "accounts-db", "数据库", origin, ServiceRegistry.Protocol.DATABASE_V2, 5, false)));
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("account-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
    }
    @Test void databaseContractUsesItsOwnIdentityAndDoesNotPretendToBeHttpMetrics() throws Exception {
        databaseClient(); response.set(json.writeValueAsBytes(databaseResponse()));
        var evidence = new LiveMetricsTool(client).execute(context, "").getFirst();
        assertThat(evidence.data()).containsEntry("observationType", "DATABASE_POOL").containsKey("databasePool")
            .doesNotContainKeys("downstreamTimeoutRate", "downstreamP95Ms", "orderP95Ms");
        assertThat(client.scenario(registry.defaultTarget())).isEqualTo(Scenario.OBSERVED);
    }
    @Test void rejectsIncompletePoolValuesWrongStagesAndInconsistentQueryCounts() throws Exception {
        databaseClient();
        List<Consumer<ObjectNode>> changes = List.of(node -> node.put("database", "inventory-service"), node -> node.put("kind", "HTTP"),
            node -> ((ObjectNode) node.path("databasePool")).remove("queryErrorCount"), node -> ((ObjectNode) node.path("databasePool")).put("maximumConnections", 0),
            node -> ((ObjectNode) node.path("databasePool")).put("peakActiveConnections", 3), node -> ((ObjectNode) node.path("databasePool")).put("peakPendingThreads", -1),
            node -> ((ObjectNode) node.path("databasePool")).put("exhaustedSamples", 3), node -> ((ObjectNode) node.path("databasePool")).put("queryCount", 4),
            node -> ((ObjectNode) node.path("databasePool")).put("acquisitionP95Ms", "1"),
            node -> node.withArray("errors").addObject().put("timestamp", end.toString()).put("traceId", "fixture").put("level", "ERROR")
                .put("message", "Database error").put("code", "DB_CONNECTION_ACQUIRE_TIMEOUT"));
        for (var change : changes) {
            var value = databaseResponse(); change.accept(value); response.set(json.writeValueAsBytes(value));
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(IllegalStateException.class);
        }
    }

    private ObjectNode valid() {
        var node = json.createObjectNode();
        node.put("schemaVersion", 1).put("service", "checkout-service").put("downstreamService", "stock-service")
            .put("windowStart", end.minusSeconds(300).toString()).put("windowEnd", end.toString())
            .put("requestCount", 5).put("timeoutCount", 1).put("recordedRequestCount", 8)
            .put("requestP95Ms", 310.4).put("downstreamP95Ms", 310.0).put("downstreamTimeoutRate", 0.2)
            .putNull("baselineRequestP95Ms").put("synthetic", false);
        node.putArray("errors").addObject().put("timestamp", end.minusSeconds(1).toString())
            .put("traceId", "test-trace").put("level", "ERROR").put("message", "stock request timeout");
        return node;
    }

    @Test void readsV1WithoutCallingLabRoutesAndKeepsTheExactWindow() {
        assertThat(client.scenario(registry.defaultTarget())).isEqualTo(Scenario.OBSERVED);
        var snapshot = client.snapshot(context);
        assertThat(snapshot.service()).isEqualTo("checkout-service");
        assertThat(snapshot.windowStart()).isEqualTo(end.minusSeconds(300));
        assertThat(snapshot.timeoutCount()).isEqualTo(1);
        assertThat(new LiveMetricsTool(client).execute(context, "").getFirst().summary()).contains("结算服务", "商品服务").doesNotContain("订单", "库存");
    }

    @Test void refusesWrongIdentityWindowVersionCountersAndUnboundedErrors() throws Exception {
        List<Consumer<ObjectNode>> changes = List.of(
            node -> node.put("service", "order-service"), node -> node.put("downstreamService", "inventory-service"),
            node -> node.put("schemaVersion", 2), node -> node.remove("requestCount"),
            node -> node.put("windowStart", end.minusSeconds(60).toString()), node -> node.put("windowEnd", end.plusSeconds(1).toString()),
            node -> node.put("requestCount", -1), node -> node.put("timeoutCount", 6), node -> node.put("recordedRequestCount", 4),
            node -> node.put("downstreamTimeoutRate", 0.4), node -> node.put("requestP95Ms", -1),
            node -> node.put("synthetic", true), node -> node.putNull("synthetic"),
            node -> ((ObjectNode) node.withArray("errors").get(0)).put("timestamp", end.plusSeconds(1).toString()),
            node -> { var errors = node.withArray("errors"); for (int i = 0; i < 3; i++) errors.add(errors.get(0).deepCopy()); });
        for (var change : changes) {
            var node = valid(); change.accept(node); response.set(json.writeValueAsBytes(node));
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(IllegalStateException.class);
        }
        response.set(json.writeValueAsBytes(valid()).clone());
        byte[] duplicate = new String(response.get(), StandardCharsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").getBytes(StandardCharsets.UTF_8);
        response.set(duplicate);
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(IllegalStateException.class);
    }

    @Test void limitsResponseBytesAndNeverFollowsRedirects() throws Exception {
        response.set("x".repeat(32_001).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(IllegalStateException.class);
        AtomicInteger followed = new AtomicInteger();
        server.createContext("/redirect-target", exchange -> { followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.removeContext("/triage/observations");
        server.createContext("/triage/observations", exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirect-target");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(IllegalStateException.class);
        assertThat(followed).hasValue(0);
    }
}
