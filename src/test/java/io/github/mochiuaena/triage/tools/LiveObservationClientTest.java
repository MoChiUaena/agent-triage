package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
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
    @Test void registeredContextPathStillReadsOnlyTheFixedObservationRoute() throws Exception {
        server.removeContext("/triage/observations");
        var accesses = new AtomicInteger();
        server.createContext("/petclinic/triage/observations", exchange -> {
            accesses.incrementAndGet();
            byte[] body = response.get();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/petclinic";
        registry = new ServiceRegistry(new ObservationSource("LIVE", "http://127.0.0.1:" + server.getAddress().getPort()),
            List.of(new ServiceRegistry.Config("checkout-service", "结算服务", "stock-service", "商品服务",
                base, ServiceRegistry.Protocol.OBSERVATIONS_V1, 15, false)));
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("checkout-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
        assertThat(client.snapshot(context).requestCount()).isEqualTo(5);
        assertThat(accesses).hasValue(1);
    }
    @Test void registeredTokenIsSentOnlyToTheConfiguredObservationService() throws Exception {
        String token = "agent_token_CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC";
        var authorization = new AtomicReference<String>();
        var plainAuthorization = new AtomicReference<String>();
        server.removeContext("/triage/observations");
        server.createContext("/triage/observations", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("X-Triage-Observation-Token"));
            byte[] body = response.get();
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.createContext("/plain/triage/observations", exchange -> {
            plainAuthorization.set(exchange.getRequestHeaders().getFirst("X-Triage-Observation-Token"));
            byte[] body = json.writeValueAsBytes(valid().put("service", "plain-service"));
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var env = new MockEnvironment().withProperty("triage.services[0].id", "checkout-service")
            .withProperty("triage.services[0].downstream-id", "stock-service")
            .withProperty("triage.services[0].base-url", origin)
            .withProperty("triage.services[0].access-token", token)
            .withProperty("triage.services[1].id", "plain-service")
            .withProperty("triage.services[1].downstream-id", "stock-service")
            .withProperty("triage.services[1].base-url", origin + "/plain");
        registry = new ServiceRegistry(new ObservationSource("LIVE", origin), env);
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("checkout-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
        assertThat(client.snapshot(context).requestCount()).isEqualTo(5);
        assertThat(authorization).hasValue(token);
        var plain = new ToolContext("plain-service", 5, Scenario.OBSERVED, end, registry.require("plain-service"));
        assertThat(client.snapshot(plain).requestCount()).isEqualTo(5);
        assertThat(plainAuthorization).hasValue(null);
        assertThat(registry.views().toString()).doesNotContain(token);
        assertThat(registry.defaultTarget().toString()).doesNotContain(token);
    }
    @Test void databaseAliasReadsOnlyTheFixedLoopbackDatabaseEndpoint() throws Exception {
        var accesses = new AtomicInteger();
        server.createContext("/triage/database-observations", exchange -> {
            accesses.incrementAndGet();
            byte[] body = json.writeValueAsBytes(databaseResponse());
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        registry = new ServiceRegistry(new ObservationSource("LIVE", origin), List.of(new ServiceRegistry.Config(
            "account-service", "账户服务", "accounts-db", "数据库", origin, ServiceRegistry.Protocol.DATABASE_V2, 5, false, true)));
        client = new LiveObservationClient(registry, json);
        context = new ToolContext("account-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
        var value = client.snapshot(context);
        assertThat(value.databasePool().queryCount()).isEqualTo(5);
        assertThat(accesses).hasValue(1);
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
    private io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint endpoint() throws Exception {
        String identity = String.join("\0", "GET", "/api/orders/{id}", "example.OrderController", "order", "java.lang.String");
        String id = "EP-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        return new io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint(id, "GET", "/api/orders/{id}", "example.OrderController", "order", List.of("java.lang.String"), "MVC_SELECTED");
    }
    private ObjectNode v3() throws Exception {
        var value = valid(); value.put("schemaVersion", 3).put("kind", "HTTP_ENDPOINTS").putNull("endpoint").put("unattributedRequestCount", 0).put("otherEndpointRequestCount", 0);
        var summary = value.putArray("endpoints").addObject(); summary.set("endpoint", json.valueToTree(endpoint()));
        summary.put("requestCount", 5).put("timeoutCount", 1).put("requestP95Ms", 310.4).put("downstreamP95Ms", 310.0);
        return value;
    }
    private void endpointClient() {
        server.createContext("/triage/endpoint-observations", exchange -> { byte[] body = response.get(); exchange.sendResponseHeaders(200, body.length); try (var out = exchange.getResponseBody()) { out.write(body); } });
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        registry = new ServiceRegistry(new ObservationSource("LIVE", origin), List.of(new ServiceRegistry.Config("checkout-service", "结算服务", "stock-service", "商品服务", origin, ServiceRegistry.Protocol.OBSERVATIONS_V3, 15, false)));
        client = new LiveObservationClient(registry, json); context = new ToolContext("checkout-service", 5, Scenario.OBSERVED, end, registry.defaultTarget());
    }
    private ObjectNode located() throws Exception {
        var value = v3();
        var location = ((ObjectNode) value.at("/errors/0")).putObject("failureLocation").put("kind", "HTTP_CLIENT_FAILURE").put("truncated", false);
        location.putArray("exceptionTypes").add("java.net.SocketTimeoutException");
        location.putArray("frames").addObject().put("className", "privatefixture.Gateway").put("methodName", "lookup").put("fileName", "Gateway.java").put("lineNumber", 42);
        return value;
    }
    @Test void v3FailureLocationsRemainLocalAndLegacyProtocolsCannotAcceptThem() throws Exception {
        endpointClient(); response.set(json.writeValueAsBytes(located()));
        var evidence = new LiveErrorLogsTool(client).execute(context, "").getFirst();
        assertThat(evidence.data()).containsKey("failureLocations");
        var projected = io.github.mochiuaena.triage.model.ModelEvidence.project(evidence);
        assertThat(projected.data()).doesNotContainKey("failureLocations");
        assertThat(json.writeValueAsString(projected)).doesNotContain("privatefixture.Gateway", "SocketTimeoutException", "Gateway.java");
        var value = valid(); value.set("errors", located().get("errors")); response.set(json.writeValueAsBytes(value));
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        var legacy = new ServiceRegistry(new ObservationSource("LIVE", origin), List.of(new ServiceRegistry.Config("checkout-service", "结算服务", "stock-service", "商品服务", origin, ServiceRegistry.Protocol.OBSERVATIONS_V1, 15, false)));
        var legacyContext = new ToolContext("checkout-service", 5, Scenario.OBSERVED, end, legacy.defaultTarget());
        assertThatThrownBy(() -> new LiveObservationClient(legacy, json).snapshot(legacyContext)).isInstanceOf(ObservationFailure.class);
    }
    @Test void failureLocationValidationRejectsMessagesPathsWrongTypesAndUnboundedFrames() throws Exception {
        endpointClient();
        List<Consumer<ObjectNode>> mutations = List.of(
            node -> ((ObjectNode) node.at("/errors/0/failureLocation")).put("kind", "METHOD_EXECUTED"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation")).put("message", "private-exception-message"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation")).remove("truncated"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("fileName", "../Gateway.java"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("className", "private\nmalformed"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("lineNumber", "42"),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("lineNumber", -1),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("lineNumber", 1.5),
            node -> ((ObjectNode) node.at("/errors/0/failureLocation/frames/0")).put("sourceHash", "private-hash"),
            node -> { var frames = (com.fasterxml.jackson.databind.node.ArrayNode) node.at("/errors/0/failureLocation/frames"); for (int i = 0; i < 8; i++) frames.add(frames.get(0).deepCopy()); },
            node -> ((ObjectNode) node.at("/errors/0/failureLocation")).putArray("exceptionTypes").add("private/body"));
        for (var mutation : mutations) {
            var value = located(); mutation.accept(value); response.set(json.writeValueAsBytes(value));
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class, error -> assertThat(error.code()).isEqualTo("OBSERVATION_CONTRACT"));
        }
        var unknown = located(); ((ObjectNode) unknown.at("/errors/0/failureLocation/frames/0")).putNull("fileName").putNull("lineNumber"); response.set(json.writeValueAsBytes(unknown));
        assertThat(client.snapshot(context).errors().getFirst().failureLocation().frames().getFirst().lineNumber()).isNull();
    }
    @Test void endpointAndFailureSourceDigestsAreLocalOptionalFieldsWithStrictHashValidation() throws Exception {
        endpointClient(); var value = located();
        ((ObjectNode) value.at("/endpoints/0/endpoint")).put("sourceHash", "a".repeat(64));
        ((ObjectNode) value.at("/errors/0/failureLocation/frames/0")).put("sourceHash", "b".repeat(64));
        response.set(json.writeValueAsBytes(value));
        assertThat(client.snapshot(context).requestDetails().endpoints().getFirst().endpoint().sourceHash()).isEqualTo("a".repeat(64));
        var metrics = new LiveMetricsTool(client).execute(context, "").getFirst();
        var logs = new LiveErrorLogsTool(client).execute(context, "").getFirst();
        assertThat(json.writeValueAsString(io.github.mochiuaena.triage.model.ModelEvidence.project(List.of(metrics, logs))))
            .doesNotContain("sourceHash", "a".repeat(64), "b".repeat(64));
        ((ObjectNode) value.at("/endpoints/0/endpoint")).put("sourceHash", "invalid"); response.set(json.writeValueAsBytes(value));
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(ObservationFailure.class);
    }
    @Test void endpointMetadataIsValidatedAndSelectionCannotChangeItsHandlerIdentity() throws Exception {
        endpointClient(); var value = v3(); response.set(json.writeValueAsBytes(value));
        var all = client.snapshot(context);
        assertThat(all.requestDetails().endpoints().getFirst().endpoint()).isEqualTo(endpoint());
        value.set("endpoint", json.valueToTree(endpoint())); response.set(json.writeValueAsBytes(value));
        var selected = new ToolContext(context.service(), 5, Scenario.OBSERVED, end, registry.defaultTarget(), endpoint());
        assertThat(client.snapshot(selected).requestDetails().endpoint()).isEqualTo(endpoint());
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOf(ObservationFailure.class);
    }
    @Test void endpointDetailsRejectWrongHashesMissingValuesDuplicatesAndMixedScope() throws Exception {
        endpointClient();
        List<Consumer<ObjectNode>> changes = List.of(node -> ((ObjectNode) node.at("/endpoints/0/endpoint")).put("id", "EP-" + "f".repeat(32)),
            node -> ((ObjectNode) node.at("/endpoints/0/endpoint")).put("handlerClass", "private\nmalformed"),
            node -> ((ObjectNode) node.at("/endpoints/0/endpoint")).put("stage", "METHOD_EXECUTED"),
            node -> ((ObjectNode) node.at("/endpoints/0")).remove("requestP95Ms"),
            node -> ((ObjectNode) node.at("/endpoints/0")).put("requestCount", 4),
            node -> node.withArray("endpoints").add(node.at("/endpoints/0").deepCopy()),
            node -> node.put("unattributedRequestCount", -1));
        for (var change : changes) { var value = v3(); change.accept(value); response.set(json.writeValueAsBytes(value));
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class, error -> assertThat(error.code()).isEqualTo("OBSERVATION_CONTRACT")); }
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
    @Test void versionMismatchAndBadValuesHaveDifferentSafeFailureCodes() throws Exception {
        var value = valid(); value.put("schemaVersion", 99); response.set(json.writeValueAsBytes(value));
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("OBSERVATION_VERSION"));
        value = valid(); value.put("windowEnd", end.plusSeconds(1).toString()); response.set(json.writeValueAsBytes(value));
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("OBSERVATION_CONTRACT"));
    }
    @Test void missingEndpointAccessDenialRetentionLossAndUnavailableServiceAreDistinguished() {
        var status = new AtomicInteger();
        server.removeContext("/triage/observations");
        server.createContext("/triage/observations", exchange -> {
            byte[] privateBody = "private-remote-error-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), privateBody.length);
            try (var out = exchange.getResponseBody()) { out.write(privateBody); }
        });
        for (var entry : java.util.Map.of(404, "OBSERVATION_ENDPOINT_MISSING", 403, "OBSERVATION_ACCESS_DENIED",
            422, "OBSERVATION_WINDOW_LOST", 500, "OBSERVATION_HTTP_ERROR").entrySet()) {
            status.set(entry.getKey());
            assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class, failure -> {
                assertThat(failure.code()).isEqualTo(entry.getValue());
                assertThat(failure.getMessage()).doesNotContain("private-remote-error-body", "http://");
            });
        }
        server.stop(0);
        assertThatThrownBy(() -> client.snapshot(context)).isInstanceOfSatisfying(ObservationFailure.class,
            failure -> assertThat(failure.code()).isEqualTo("OBSERVATION_UNAVAILABLE"));
    }
}
