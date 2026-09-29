package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "triage.observation.source=LIVE",
    "spring.datasource.url=jdbc:h2:mem:registered;DB_CLOSE_DELAY=-1", "triage.settings.key-file=target/registered-test-key"
})
class RegisteredServiceApiTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger labRequests = new AtomicInteger();
    private static final AtomicInteger schemaVersion = new AtomicInteger(1);
    private static final List<HttpServer> servers = new ArrayList<>();
    @Autowired TestRestTemplate http;

    @DynamicPropertySource static void targets(DynamicPropertyRegistry properties) throws Exception {
        for (int i = 0; i < 2; i++) {
            String id = i == 0 ? "checkout-service" : "billing-service";
            boolean timeout = i == 0;
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/triage/observations", exchange -> {
                requests.incrementAndGet();
                var query = new HashMap<String, String>();
                for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2); query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                }
                Instant end = Instant.parse(query.get("endTime"));
                var data = new LinkedHashMap<String, Object>();
                data.put("schemaVersion", schemaVersion.get()); data.put("service", id); data.put("downstreamService", "stock-service");
                data.put("windowStart", end.minusSeconds(Integer.parseInt(query.get("windowMinutes")) * 60L)); data.put("windowEnd", end);
                data.put("requestCount", 5); data.put("timeoutCount", timeout ? 1 : 0); data.put("recordedRequestCount", 5);
                data.put("requestP95Ms", timeout ? 310 : 20); data.put("downstreamP95Ms", timeout ? 305 : 15);
                data.put("downstreamTimeoutRate", timeout ? 0.2 : 0); data.put("baselineRequestP95Ms", null);
                data.put("errors", timeout ? List.of(Map.of("timestamp", end.minusSeconds(1), "traceId", "checkout-test-trace",
                    "level", "ERROR", "message", "stock request timeout")) : List.of()); data.put("synthetic", false);
                byte[] body = JSON.writeValueAsBytes(data);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.createContext("/lab", exchange -> { labRequests.incrementAndGet(); exchange.sendResponseHeaders(404, -1); exchange.close(); });
            server.start(); servers.add(server);
            String prefix = "triage.services[" + i + "].";
            properties.add(prefix + "id", () -> id);
            properties.add(prefix + "name", () -> timeout ? "结算服务" : "账单服务");
            properties.add(prefix + "downstream-id", () -> "stock-service");
            properties.add(prefix + "downstream-name", () -> "商品服务");
            properties.add(prefix + "base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
            properties.add(prefix + "max-window-minutes", () -> timeout ? "15" : "5");
        }
    }
    @AfterAll static void stop() { servers.forEach(server -> server.stop(0)); }
    @AfterEach void restoreVersion() { schemaVersion.set(1); }

    @Test void configurationAndPersistedFailureExplainVersionMismatchWithoutRevealingTheOrigin() {
        schemaVersion.set(99);
        String config = http.getForObject("/api/config?service=checkout-service", String.class);
        assertThat(config).contains("\"observationAvailable\":false", "OBSERVATION_VERSION", "版本")
            .doesNotContain("http://", "baseUrl");
        var response = http.postForEntity("/api/runs", Map.of("question", "商品查询为什么慢？", "service", "checkout-service", "windowMinutes", 5), Run.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(5)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        Run run = http.getForObject("/api/runs/" + id, Run.class);
        assertThat(run.status()).isEqualTo(Status.FAILED);
        assertThat(run.failure().code()).isEqualTo("OBSERVATION_VERSION");
        assertThat(run.failure().message()).contains("版本").doesNotContain("http://");
        assertThat(run.diagnosis()).isNull();
    }

    @Test void switchingTargetsUsesOnlyTheirOwnObservationsAndPersistsLabels() {
        for (String id : List.of("checkout-service", "billing-service")) {
            var config = http.getForObject("/api/config?service=" + id, String.class);
            assertThat(config).contains("OBSERVATIONS_V1", "\"labEnabled\":false", "\"observationAvailable\":true")
                .doesNotContain("http://", "baseUrl");
            var created = http.postForEntity("/api/runs", Map.of("question", id + " 为什么慢？", "service", id, "windowMinutes", 5), Run.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            UUID runId = created.getBody().id();
            await().atMost(Duration.ofSeconds(5)).until(() -> http.getForObject("/api/runs/" + runId, Run.class).status().terminal());
            Run run = http.getForObject("/api/runs/" + runId, Run.class);
            assertThat(run.status()).isEqualTo(Status.SUCCEEDED);
            assertThat(run.scenario()).isEqualTo(Scenario.OBSERVED);
            assertThat(run.serviceInfo().id()).isEqualTo(id);
            assertThat(run.evidence()).allSatisfy(item -> assertThat(item.data()).containsEntry("service", id));
            assertThat(run.evidence()).extracting(Evidence::id).contains("DOC-DOWNSTREAM-TIMEOUT#v3", "DOC-HEALTHY-BASELINE#v3");
            assertThat(run.diagnosis().possibleCauses().getFirst().text()).doesNotContain("订单", "库存");
            var metrics = run.evidence().stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
            assertThat(((Number) metrics.data().get("timeoutCount")).intValue()).isEqualTo(id.equals("checkout-service") ? 1 : 0);
            assertThat(http.getForObject("/api/runs", RunSummary[].class)).anySatisfy(summary -> {
                assertThat(summary.id()).isEqualTo(runId); assertThat(summary.serviceInfo()).isEqualTo(run.serviceInfo());
            });
        }
        assertThat(labRequests).hasValue(0);
    }

    @Test void rejectsUnknownServiceAndOversizedWindowBeforeAnyNetworkRequest() {
        int before = requests.get();
        for (var body : List.of(Map.of("question", "排查超时", "service", "unknown", "windowMinutes", 5),
            Map.of("question", "排查超时", "service", "billing-service", "windowMinutes", 15)))
            assertThat(http.postForEntity("/api/runs", body, String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/config?service=unknown", String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(requests).hasValue(before);
    }

    @Test void cannotWriteToReadonlyTargetEvenWithTheLabHeader() {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Lab", "1");
        int before = requests.get();
        var response = http.postForEntity("/api/live-lab/traffic", new HttpEntity<>(Map.of("service", "checkout-service",
            "scenario", "DOWNSTREAM_TIMEOUT", "count", 5), headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(requests).hasValue(before); assertThat(labRequests).hasValue(0);
    }
}
