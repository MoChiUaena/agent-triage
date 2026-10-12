package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "triage.observation.source=LIVE",
    "spring.datasource.url=jdbc:h2:mem:multi-downstream;DB_CLOSE_DELAY=-1",
    "triage.settings.key-file=target/multi-downstream-test-key"
})
class MultiDownstreamRunApiTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static HttpServer server;
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void target(DynamicPropertyRegistry properties) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/triage/endpoint-observations", exchange -> {
            var query = new java.util.HashMap<String, String>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            Instant end = Instant.parse(query.get("endTime"));
            int minutes = Integer.parseInt(query.get("windowMinutes"));
            var data = new LinkedHashMap<String, Object>();
            data.put("schemaVersion", 5); data.put("kind", "HTTP_ENDPOINTS");
            data.put("service", "checkout-service"); data.put("downstreamService", null);
            data.put("windowStart", end.minusSeconds(minutes * 60L)); data.put("windowEnd", end);
            data.put("requestCount", 5); data.put("timeoutCount", 1); data.put("recordedRequestCount", 5);
            data.put("requestP95Ms", 310.0); data.put("downstreamP95Ms", 300.0);
            data.put("downstreamTimeoutRate", 0.2); data.put("baselineRequestP95Ms", null);
            data.put("synthetic", false); data.put("endpoint", null); data.put("endpoints", List.of());
            data.put("unattributedRequestCount", 5); data.put("otherEndpointRequestCount", 0);
            data.put("errors", List.of(Map.of("timestamp", end.minusSeconds(1), "traceId", "api-test-trace",
                "level", "ERROR", "message", "request timeout")));
            data.put("downstreams", List.of(
                Map.of("id", "inventory-service", "requestCount", 2, "timeoutCount", 1,
                    "downstreamP95Ms", 300.0, "downstreamTimeoutRate", 0.5,
                    "responseStatuses", Map.of("informational", 0, "successful", 1, "redirection", 0,
                        "clientError", 0, "serverError", 0, "unknown", 1)),
                Map.of("id", "payment-service", "requestCount", 3, "timeoutCount", 0,
                    "downstreamP95Ms", 20.0, "downstreamTimeoutRate", 0.0,
                    "responseStatuses", Map.of("informational", 0, "successful", 2, "redirection", 0,
                        "clientError", 0, "serverError", 1, "unknown", 0))));
            byte[] body = JSON.writeValueAsBytes(data);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        properties.add("triage.services[0].id", () -> "checkout-service");
        properties.add("triage.services[0].name", () -> "结算服务");
        properties.add("triage.services[0].base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
        properties.add("triage.services[0].protocol", () -> "OBSERVATIONS_V5");
        properties.add("triage.services[0].downstreams[0].id", () -> "inventory-service");
        properties.add("triage.services[0].downstreams[0].name", () -> "库存服务");
        properties.add("triage.services[0].downstreams[1].id", () -> "payment-service");
        properties.add("triage.services[0].downstreams[1].name", () -> "支付服务");
    }
    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test void configuredIdentitiesAndPerDownstreamEvidenceSurviveTheApiAndHistory() {
        JsonNode config = http.getForObject("/api/config", JsonNode.class);
        assertThat(config.at("/observationAvailable").asBoolean()).isTrue();
        assertThat(config.at("/services/0/protocol").asText()).isEqualTo("OBSERVATIONS_V5");
        assertThat(config.at("/services/0/downstreams/1/name").asText()).isEqualTo("支付服务");
        assertThat(config.toString()).doesNotContain("baseUrl", "127.0.0.1");

        var created = http.postForEntity("/api/runs", Map.of("question", "结算服务请求为什么变慢？",
            "service", "checkout-service", "windowMinutes", 5), Run.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = created.getBody().id();
        await().atMost(Duration.ofSeconds(8)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        Run saved = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(saved.status()).isEqualTo(Status.SUCCEEDED);
        assertThat(saved.serviceInfo().downstreams()).extracting(Downstream::id)
            .containsExactly("inventory-service", "payment-service");
        var metrics = saved.evidence().stream().filter(item -> item.source().equals("read_service_metrics")).findFirst().orElseThrow();
        assertThat(metrics.data()).containsEntry("observationType", "HTTP_DOWNSTREAMS");
        assertThat(JSON.valueToTree(metrics.data()).at("/downstreams/0/timeoutCount").asInt()).isEqualTo(1);
        assertThat(JSON.valueToTree(metrics.data()).at("/downstreams/1/timeoutCount").asInt()).isZero();
        assertThat(JSON.valueToTree(metrics.data()).at("/downstreams/1/responseStatuses/serverError").asInt()).isEqualTo(1);
        assertThat(saved.diagnosis().possibleCauses().getFirst().text()).contains("库存服务").doesNotContain("支付服务");
        assertThat(http.getForObject("/api/runs", RunSummary[].class)).filteredOn(summary -> summary.id().equals(id))
            .singleElement().satisfies(summary -> assertThat(summary.serviceInfo().downstreams())
                .isEqualTo(saved.serviceInfo().downstreams()));
    }
}
